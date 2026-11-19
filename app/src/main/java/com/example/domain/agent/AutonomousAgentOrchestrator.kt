package com.example.domain.agent

import com.example.android.accessibility.NexaAccessibilityService
import com.example.data.repository.ConnectionsRepository
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentExecutionContext
import com.example.domain.model.AgentExecutionPhase
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.AmbiguityCandidateItem
import com.example.domain.model.AmbiguitySelectionRequest
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ChatMessage
import com.example.domain.model.ComputerUseFailureCategory
import com.example.domain.model.ExecutionPlan
import com.example.domain.model.ExecutionStep
import com.example.domain.model.ExecutionStepState
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ProviderConfig
import com.example.domain.model.StepKind
import com.example.domain.model.TaskSummary
import com.example.domain.model.TaskVerificationSummary
import com.example.domain.model.TokenUsage
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolCallResult
import com.example.domain.model.ToolDispatchDiagnostic
import com.example.domain.model.ToolExecutionStatus
import com.example.domain.model.UserUnlockWaitRequest
import com.example.domain.model.VerificationLevel
import com.example.domain.provider.ProviderRequest
import com.example.domain.provider.ProviderStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

fun interface ProviderStreamCaller {
    fun stream(provider: ProviderConfig, request: ProviderRequest): Flow<ProviderStreamEvent>
}

interface AutonomousAgentOrchestrator {
    fun runTurn(
        context: AgentExecutionContext,
        historySnapshot: List<ChatMessage>,
        attachments: List<AttachmentPayload>,
        control: AgentControlHandle = NoOpAgentControlHandle
    ): Flow<AgentEvent>
}

object NoOpAgentControlHandle : AgentControlHandle {
    override fun isPausedNow(): Boolean = false
    override suspend fun awaitIfPaused() = Unit
    override suspend fun requestApproval(request: com.example.domain.model.ToolApprovalRequest): ApprovalDecision =
        ApprovalDecision.REJECT
}

class DefaultAutonomousAgentOrchestrator(
    private val streamCaller: ProviderStreamCaller,
    private val toolRegistry: ToolRegistry,
    private val safetyPolicyManager: SafetyPolicyManager,
    private val executionRuntime: ToolExecutionRuntime,
    private val contextBuilder: ConversationContextBuilder = ConversationContextBuilder(),
    private val timeoutDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val diagnosticSink: ((ToolDispatchDiagnostic) -> Unit)? = null,
    private val foregroundPackageProvider: () -> String? = {
        NexaAccessibilityService.runtimeState.value.activePackageName
    },
    private val appLockPollIntervalMillis: Long = 1_000L,
    private val onTaskFinished: (() -> Unit)? = null
) : AutonomousAgentOrchestrator {

    constructor(
        connectionsRepository: ConnectionsRepository,
        toolRegistry: ToolRegistry,
        safetyPolicyManager: SafetyPolicyManager,
        executionRuntime: ToolExecutionRuntime,
        contextBuilder: ConversationContextBuilder = ConversationContextBuilder(),
        diagnosticSink: ((ToolDispatchDiagnostic) -> Unit)? = null,
        foregroundPackageProvider: () -> String? = {
            NexaAccessibilityService.runtimeState.value.activePackageName
        },
        appLockPollIntervalMillis: Long = 1_000L,
        onTaskFinished: (() -> Unit)? = null
    ) : this(
        streamCaller = ProviderStreamCaller { provider, request ->
            connectionsRepository.streamChat(provider, request)
        },
        toolRegistry = toolRegistry,
        safetyPolicyManager = safetyPolicyManager,
        executionRuntime = executionRuntime,
        contextBuilder = contextBuilder,
        diagnosticSink = diagnosticSink,
        foregroundPackageProvider = foregroundPackageProvider,
        appLockPollIntervalMillis = appLockPollIntervalMillis,
        onTaskFinished = onTaskFinished
    )

    override fun runTurn(
        context: AgentExecutionContext,
        historySnapshot: List<ChatMessage>,
        attachments: List<AttachmentPayload>,
        control: AgentControlHandle
    ): Flow<AgentEvent> = channelFlow {
        val state = MutableRunState(
            taskId = context.taskId,
            goal = context.userPrompt.trim().ifBlank { "Nexa Task" },
            workingHistory = historySnapshot.toMutableList()
        )

        send(
            AgentEvent.StatusChanged(
                taskId = context.taskId,
                status = AgentRunStatus.PREPARING,
                message = "Preparing execution context...",
                phase = AgentExecutionPhase.UNDERSTANDING_REQUEST,
                currentSubgoal = state.goal.take(80)
            )
        )

        val timedOut = AtomicBoolean(false)
        var finishedNormally = false
        try {
            coroutineScope {
                val worker = launch {
                    executeLoop(this@channelFlow, context, attachments, control, state)
                    finishedNormally = true
                }
                val watchdog = launch(timeoutDispatcher) {
                    var activeElapsedMs = 0L
                    var lastTickMs = System.currentTimeMillis()
                    while (!finishedNormally) {
                        delay(100L)
                        val now = System.currentTimeMillis()
                        val delta = (now - lastTickMs).coerceIn(0L, 1_000L)
                        lastTickMs = now
                        // Pause active-execution clock whenever user is paused, approving, unlocking AppLock, or choosing ambiguity.
                        if (!control.isPausedNow() && !state.isWaitingOnUser) {
                            activeElapsedMs += delta
                            if (activeElapsedMs >= context.limits.taskTimeoutMillis) {
                                timedOut.set(true)
                                worker.cancel(
                                    CancellationException("Task timed out after ${context.limits.taskTimeoutMillis}ms of active execution")
                                )
                                break
                            }
                        }
                    }
                }
                worker.join()
                watchdog.cancel()
            }

            if (timedOut.get()) {
                currentCoroutineContext().ensureActive()
                val timeoutMsg = "Task timed out after ${context.limits.taskTimeoutMillis / 1000L}s of active execution before completion."
                state.steps.add(
                    ExecutionStep(
                        id = "step_timeout_${state.steps.size + 1}",
                        title = "Task timeout reached",
                        detail = timeoutMsg,
                        state = ExecutionStepState.FAILED,
                        kind = StepKind.ERROR
                    )
                )
                val verification = buildVerificationSummary(state, forceUnverified = true)
                val plan = buildPlanIfAny(state, verification)
                send(
                    AgentEvent.Failed(
                        taskId = context.taskId,
                        partialText = state.latestStreamingText,
                        errorMessage = timeoutMsg,
                        plan = plan,
                        verification = verification,
                        usage = state.totalUsage,
                        taskSummary = buildTaskSummary(state, AgentTaskStatus.FAILED)
                    )
                )
            }
        } finally {
            runCatching { onTaskFinished?.invoke() }
        }
    }

    private suspend fun executeLoop(
        collector: ProducerScope<AgentEvent>,
        context: AgentExecutionContext,
        attachments: List<AttachmentPayload>,
        control: AgentControlHandle,
        state: MutableRunState
    ) {
        val modelId = context.provider.selectedModelId?.takeIf { it.isNotBlank() }
            ?: run {
                val verification = buildVerificationSummary(state, forceUnverified = true)
                collector.send(
                    AgentEvent.Failed(
                        taskId = context.taskId,
                        partialText = "",
                        errorMessage = "No active model selected for ${context.provider.providerType.displayName}.",
                        plan = null,
                        verification = verification,
                        usage = null,
                        taskSummary = null
                    )
                )
                return
            }

        val allowToolsForProvider = context.provider.effectiveSupportsTools && context.availableTools.isNotEmpty()
        var pendingVisualAttachments = emptyList<AttachmentPayload>()
        val progressMonitor = TaskProgressMonitor(
            objective = state.goal,
            noProgressLimit = context.limits.noProgressRetryLimit
        )

        for (iteration in 1..context.limits.maxIterations) {
            currentCoroutineContext().ensureActive()
            checkPause(collector, context.taskId, control)

            val contextLimitTokens = context.limits.maxContextTokens
                ?: context.provider.selectedModelInfo?.contextLengthTokens
            val contextResult = contextBuilder.buildContext(
                history = state.workingHistory,
                systemPrompt = context.systemPrompt,
                modelContextLengthTokens = contextLimitTokens
            )

            if (contextResult.messages.isEmpty()) {
                val verification = buildVerificationSummary(state, forceUnverified = true)
                collector.send(
                    AgentEvent.Failed(
                        taskId = context.taskId,
                        partialText = state.latestStreamingText,
                        errorMessage = "No eligible conversation messages to send.",
                        plan = buildPlanIfAny(state, verification),
                        verification = verification,
                        usage = state.totalUsage,
                        taskSummary = buildTaskSummary(state, AgentTaskStatus.FAILED)
                    )
                )
                return
            }

            val thinkingStatus = if (iteration == 1) AgentRunStatus.THINKING else AgentRunStatus.REPLANNING
            val thinkingPhase = if (iteration == 1) AgentExecutionPhase.PLANNING else AgentExecutionPhase.REPLANNING
            val thinkingMessage = if (iteration == 1) {
                "Thinking with ${modelId.substringAfterLast("/")}..."
            } else {
                "Observing tool results & replanning (step $iteration)..."
            }
            collector.send(
                AgentEvent.StatusChanged(
                    taskId = context.taskId,
                    status = thinkingStatus,
                    message = thinkingMessage,
                    phase = thinkingPhase,
                    currentSubgoal = state.goal.take(80)
                )
            )

            state.latestStreamingText = ""
            if (iteration > 1) {
                collector.send(AgentEvent.TextDelta(context.taskId, ""))
            }

            val iterationAttachments = if (iteration == 1) {
                attachments + pendingVisualAttachments
            } else {
                pendingVisualAttachments
            }
            pendingVisualAttachments = emptyList()

            val request = ProviderRequest(
                modelId = modelId,
                messages = contextResult.messages,
                systemPrompt = context.systemPrompt,
                attachments = iterationAttachments,
                modelInfo = context.provider.selectedModelInfo,
                tools = if (allowToolsForProvider) context.availableTools else emptyList(),
                allowTools = allowToolsForProvider
            )

            val turnText = StringBuilder()
            val iterToolCalls = mutableListOf<ToolCall>()
            var iterUsage: TokenUsage? = null
            var streamFailure: String? = null

            streamCaller.stream(context.provider, request).collect { streamEvent ->
                currentCoroutineContext().ensureActive()
                when (streamEvent) {
                    is ProviderStreamEvent.Delta -> {
                        turnText.append(streamEvent.text)
                        state.latestStreamingText = turnText.toString()
                        collector.send(AgentEvent.TextDelta(context.taskId, state.latestStreamingText))
                    }
                    is ProviderStreamEvent.ToolCallDelta -> Unit
                    is ProviderStreamEvent.ToolCallsCompleted -> {
                        iterToolCalls.clear()
                        iterToolCalls.addAll(streamEvent.toolCalls)
                        if (streamEvent.usage != null) iterUsage = streamEvent.usage
                    }
                    is ProviderStreamEvent.Usage -> iterUsage = streamEvent.usage
                    is ProviderStreamEvent.Completed -> if (streamEvent.usage != null) iterUsage = streamEvent.usage
                    is ProviderStreamEvent.Failed -> streamFailure = streamEvent.error.userMessage
                }
            }

            diagnosticSink?.invoke(
                ToolDispatchDiagnostic(
                    providerType = context.provider.providerType,
                    modelId = modelId,
                    toolsEnabled = allowToolsForProvider,
                    availableToolNames = request.tools.map { it.name },
                    serializedToolNames = request.tools.map { it.name },
                    toolChoiceSent = if (allowToolsForProvider && request.tools.isNotEmpty()) "auto" else "none",
                    returnedToolCallNames = iterToolCalls.map { it.toolName },
                    responseError = streamFailure
                )
            )

            state.totalUsage = mergeUsage(state.totalUsage, iterUsage)

            val err = streamFailure
            if (err != null) {
                state.steps.add(
                    ExecutionStep(
                        id = "step_err_${state.steps.size + 1}",
                        title = "Model request failed",
                        detail = err,
                        state = ExecutionStepState.FAILED,
                        kind = StepKind.ERROR
                    )
                )
                val verification = buildVerificationSummary(state, forceUnverified = true)
                val plan = buildPlanIfAny(state, verification)
                collector.send(
                    AgentEvent.Failed(
                        taskId = context.taskId,
                        partialText = turnText.toString(),
                        errorMessage = err,
                        plan = plan,
                        verification = verification,
                        usage = state.totalUsage,
                        taskSummary = buildTaskSummary(state, AgentTaskStatus.FAILED)
                    )
                )
                return
            }

            if (iterToolCalls.isEmpty()) {
                val finalReply = turnText.toString()
                if (finalReply.isBlank()) {
                    val emptyErr = "Provider returned an empty response. Please retry or check model compatibility."
                    val verification = buildVerificationSummary(state, forceUnverified = true)
                    collector.send(
                        AgentEvent.Failed(
                            taskId = context.taskId,
                            partialText = "",
                            errorMessage = emptyErr,
                            plan = buildPlanIfAny(state, verification),
                            verification = verification,
                            usage = state.totalUsage,
                            taskSummary = buildTaskSummary(state, AgentTaskStatus.FAILED)
                        )
                    )
                    return
                }

                collector.send(
                    AgentEvent.StatusChanged(
                        taskId = context.taskId,
                        status = AgentRunStatus.VERIFYING,
                        message = "Verifying completion...",
                        phase = AgentExecutionPhase.VERIFYING
                    )
                )
                if (state.requestedToolCalls > 0) {
                    val interimVerification = buildVerificationSummary(state)
                    state.steps.add(
                        ExecutionStep(
                            id = "step_verify_${state.steps.size + 1}",
                            title = if (interimVerification.objectiveVerified && interimVerification.isVerified) {
                                "Task verification passed"
                            } else {
                                "Task finished with unverified or blocked steps"
                            },
                            detail = interimVerification.summaryNote,
                            state = if (interimVerification.objectiveVerified && interimVerification.isVerified) {
                                ExecutionStepState.COMPLETED
                            } else {
                                ExecutionStepState.FAILED
                            },
                            kind = StepKind.VERIFICATION
                        )
                    )
                }
                val verification = buildVerificationSummary(state)
                val isPartial = state.requestedToolCalls > 0 && (
                    !verification.objectiveVerified ||
                        verification.blockedCount > 0 ||
                        verification.unverifiedCompletedCount > 0
                    )
                val taskStatus = if (isPartial) {
                    AgentTaskStatus.PARTIALLY_COMPLETED
                } else {
                    AgentTaskStatus.COMPLETED
                }
                val plan = buildPlanIfAny(state, verification)
                if (plan != null) {
                    collector.send(AgentEvent.PlanUpdated(context.taskId, plan))
                }
                collector.send(
                    AgentEvent.Completed(
                        taskId = context.taskId,
                        finalText = finalReply,
                        plan = plan,
                        verification = verification,
                        usage = state.totalUsage,
                        taskSummary = buildTaskSummary(state, taskStatus),
                        partiallyCompleted = isPartial
                    )
                )
                return
            }

            if (state.totalToolCalls + iterToolCalls.size > context.limits.maxToolCalls) {
                val budgetMsg = "Tool-call budget reached (${context.limits.maxToolCalls} calls). Stopping execution safely without unverified guesses."
                state.steps.add(
                    ExecutionStep(
                        id = "step_budget_${state.steps.size + 1}",
                        title = "Tool-call limit reached",
                        detail = budgetMsg,
                        state = ExecutionStepState.FAILED,
                        kind = StepKind.ERROR
                    )
                )
                val verification = buildVerificationSummary(state, forceUnverified = true)
                val plan = buildPlanIfAny(state, verification)
                collector.send(
                    AgentEvent.Failed(
                        taskId = context.taskId,
                        partialText = turnText.toString(),
                        errorMessage = budgetMsg,
                        plan = plan,
                        verification = verification,
                        usage = state.totalUsage,
                        taskSummary = buildTaskSummary(state, AgentTaskStatus.FAILED)
                    )
                )
                return
            }

            state.requestedToolCalls += iterToolCalls.size
            collector.send(AgentEvent.ToolSelected(context.taskId, iteration, iterToolCalls))

            val callStepIds = mutableMapOf<String, Int>()
            state.steps.add(
                ExecutionStep(
                    id = "step_intent_${iteration}_${state.steps.size + 1}",
                    title = "Turn $iteration tool plan",
                    detail = "Model selected: ${iterToolCalls.joinToString { it.toolName }}",
                    state = ExecutionStepState.COMPLETED,
                    kind = StepKind.INTENT
                )
            )
            iterToolCalls.forEach { call ->
                val stepIdx = state.steps.size
                callStepIds[call.id] = stepIdx
                state.steps.add(
                    ExecutionStep(
                        id = "step_call_${iteration}_${call.id}",
                        title = "Run ${call.toolName}",
                        detail = previewCallArguments(call, context),
                        state = ExecutionStepState.PENDING,
                        toolName = call.toolName,
                        kind = StepKind.EXECUTED
                    )
                )
            }
            emitPlanSnapshot(collector, state)

            val turnAssistantPreface = turnText.toString()
            state.workingHistory.add(
                ChatMessage(
                    id = "tmp_asst_${iteration}_${System.nanoTime()}",
                    conversationId = context.conversationId,
                    role = MessageRole.ASSISTANT,
                    content = turnAssistantPreface,
                    createdAtMillis = System.currentTimeMillis(),
                    status = MessageStatus.COMPLETE,
                    toolCalls = iterToolCalls
                )
            )

            val turnResults = mutableListOf<ToolCallResult>()

            for (call in iterToolCalls) {
                currentCoroutineContext().ensureActive()
                checkPause(collector, context.taskId, control)

                state.totalToolCalls++
                val stepIdx = callStepIds[call.id]
                val signature = "${call.toolName}:${canonicalizeJson(call.argumentsJson)}"
                val priorFailures = state.failedSignatureCounts[signature] ?: 0

                if (priorFailures >= context.limits.maxRepeatedIdenticalFailures) {
                    val loopResult = ToolCallResult(
                        callId = call.id,
                        toolName = call.toolName,
                        status = ToolExecutionStatus.FAILED,
                        outputJson = JSONObject().put("error", "Repeated failing tool call blocked to avoid infinite loop.").toString(),
                        displaySummary = "Loop guard: blocked repeated failing call",
                        errorMessage = "Blocked repeated identical failing call to '${call.toolName}'."
                    )
                    updateStep(state, stepIdx, ExecutionStepState.FAILED, loopResult.displaySummary, StepKind.ERROR)
                    state.allResults.add(loopResult)
                    turnResults.add(loopResult)
                    collector.send(AgentEvent.ToolCompleted(context.taskId, loopResult))
                    emitPlanSnapshot(collector, state)
                    continue
                }

                val tool = toolRegistry.findExecutableTool(call.toolName)
                val parsedArgs = executionRuntime.parseArgsObject(call.argumentsJson)
                val preValidationError = if (tool != null && tool.definition.isImplemented && parsedArgs != null) {
                    tool.validateArguments(parsedArgs)
                } else {
                    null
                }

                var result = if (tool == null || !tool.definition.isImplemented || parsedArgs == null || preValidationError != null) {
                    updateStep(state, stepIdx, ExecutionStepState.RUNNING, "Validating arguments...", StepKind.EXECUTED)
                    emitPlanSnapshot(collector, state)
                    collector.send(AgentEvent.ToolStarted(context.taskId, call))
                    executionRuntime.executeToolCall(
                        call = call,
                        settings = context.settings,
                        timeoutMillis = context.limits.toolTimeoutMillis,
                        maxResultChars = context.limits.maxToolResultChars
                    )
                } else {
                    when (val policy = safetyPolicyManager.evaluateToolCall(context.taskId, tool, call, parsedArgs, context.settings)) {
                        is ToolPolicyEvaluation.Deny -> {
                            ToolCallResult(
                                callId = call.id,
                                toolName = call.toolName,
                                status = ToolExecutionStatus.REJECTED,
                                outputJson = JSONObject().put("error", policy.reason).toString(),
                                displaySummary = "Blocked by safety policy: ${policy.reason}",
                                errorMessage = policy.reason
                            )
                        }
                        is ToolPolicyEvaluation.RequireUserApproval -> {
                            updateStep(
                                state,
                                stepIdx,
                                ExecutionStepState.AWAITING_CONFIRMATION,
                                "Waiting approval: ${policy.request.intentSummary}",
                                StepKind.APPROVAL
                            )
                            emitPlanSnapshot(collector, state)
                            collector.send(
                                AgentEvent.StatusChanged(
                                    taskId = context.taskId,
                                    status = AgentRunStatus.AWAITING_APPROVAL,
                                    message = "Approval needed for ${call.toolName}",
                                    phase = AgentExecutionPhase.WAITING_FOR_USER,
                                    nextPlannedAction = call.toolName
                                )
                            )
                            collector.send(AgentEvent.ApprovalRequired(context.taskId, policy.request))

                            val decision = try {
                                state.isWaitingOnUser = true
                                control.requestApproval(policy.request)
                            } finally {
                                state.isWaitingOnUser = false
                            }
                            if (decision == ApprovalDecision.REJECT) {
                                ToolCallResult(
                                    callId = call.id,
                                    toolName = call.toolName,
                                    status = ToolExecutionStatus.REJECTED,
                                    outputJson = JSONObject()
                                        .put("status", "rejected")
                                        .put("reason", "User rejected tool '${call.toolName}'.")
                                        .toString(),
                                    displaySummary = "Rejected by user",
                                    errorMessage = "User rejected tool '${call.toolName}'."
                                )
                            } else {
                                updateStep(state, stepIdx, ExecutionStepState.RUNNING, "Approved — running ${call.toolName}...", StepKind.EXECUTED)
                                emitPlanSnapshot(collector, state)
                                collector.send(AgentEvent.ToolStarted(context.taskId, call))
                                collector.send(
                                    AgentEvent.StatusChanged(
                                        taskId = context.taskId,
                                        status = statusForTool(call.toolName),
                                        message = "Executing ${call.toolName}...",
                                        phase = phaseForTool(call.toolName),
                                        nextPlannedAction = call.toolName
                                    )
                                )
                                executionRuntime.executeToolCall(
                                    call = call,
                                    settings = context.settings,
                                    timeoutMillis = context.limits.toolTimeoutMillis,
                                    maxResultChars = context.limits.maxToolResultChars
                                )
                            }
                        }
                        ToolPolicyEvaluation.Allow -> {
                            updateStep(state, stepIdx, ExecutionStepState.RUNNING, "Executing ${call.toolName}...", StepKind.EXECUTED)
                            emitPlanSnapshot(collector, state)
                            collector.send(AgentEvent.ToolStarted(context.taskId, call))
                            collector.send(
                                AgentEvent.StatusChanged(
                                    taskId = context.taskId,
                                    status = statusForTool(call.toolName),
                                    message = "Executing ${call.toolName}...",
                                    phase = phaseForTool(call.toolName),
                                    nextPlannedAction = call.toolName
                                )
                            )
                            executionRuntime.executeToolCall(
                                call = call,
                                settings = context.settings,
                                timeoutMillis = context.limits.toolTimeoutMillis,
                                maxResultChars = context.limits.maxToolResultChars
                            )
                        }
                    }
                }

                // Ambiguous app selection resolution (ask user, never guess silently).
                result = handleAmbiguityIfNeeded(collector, context, control, state, stepIdx, call, result)

                // Transsion AppLock wait & automatic resume (never bypass or relaunch locked app).
                val lockRecovery = handleAppLockIfNeeded(collector, context, control, state, stepIdx, call, result)
                result = lockRecovery.first
                val countFailure = !lockRecovery.second

                if (result.status != ToolExecutionStatus.SUCCEEDED && countFailure) {
                    state.failedSignatureCounts[signature] = priorFailures + 1
                }

                val stepState = if (result.status == ToolExecutionStatus.SUCCEEDED) {
                    ExecutionStepState.COMPLETED
                } else {
                    ExecutionStepState.FAILED
                }
                val stepKind = if (result.status == ToolExecutionStatus.SUCCEEDED) {
                    StepKind.RESULT
                } else {
                    StepKind.ERROR
                }
                updateStep(state, stepIdx, stepState, result.displaySummary, stepKind)
                state.allResults.add(result)
                turnResults.add(result)
                collector.send(AgentEvent.ToolCompleted(context.taskId, result))
                emitSubgoalProgress(collector, context.taskId, state.goal, call, result)
                emitPlanSnapshot(collector, state)

                // Track meaningful progress and stop repeated no-progress loops cleanly.
                when (val progress = progressMonitor.recordToolResult(call, result)) {
                    is ProgressCheckOutcome.NoProgressCycle -> {
                        collector.send(
                            AgentEvent.StatusChanged(
                                taskId = context.taskId,
                                status = AgentRunStatus.RECOVERING,
                                message = "Screen unchanged — adapting strategy...",
                                phase = AgentExecutionPhase.RECOVERING,
                                currentSubgoal = state.goal.take(80)
                            )
                        )
                    }
                    is ProgressCheckOutcome.HaltNoProgress -> {
                        val haltMsg = "Task paused safely to avoid an infinite loop (${progress.blockerClassification}): ${progress.reason}"
                        state.steps.add(
                            ExecutionStep(
                                id = "step_loop_guard_${state.steps.size + 1}",
                                title = "Stopped repeated no-progress cycle",
                                detail = haltMsg,
                                state = ExecutionStepState.FAILED,
                                kind = StepKind.ERROR
                            )
                        )
                        val verification = buildVerificationSummary(state, forceUnverified = true)
                        val plan = buildPlanIfAny(state, verification)
                        if (plan != null) {
                            collector.send(AgentEvent.PlanUpdated(context.taskId, plan))
                        }
                        collector.send(
                            AgentEvent.Failed(
                                taskId = context.taskId,
                                partialText = state.latestStreamingText,
                                errorMessage = haltMsg,
                                plan = plan,
                                verification = verification,
                                usage = state.totalUsage,
                                taskSummary = buildTaskSummary(state, AgentTaskStatus.FAILED)
                            )
                        )
                        return
                    }
                    else -> Unit
                }
            }

            // Condense verbose node lists from older tool messages so LLM stays focused on the latest screen.
            condenseOlderScreenPayloads(state.workingHistory)

            turnResults.forEach { res ->
                val content = res.outputJson ?: JSONObject().put("error", res.errorMessage ?: res.displaySummary).toString()
                val visual = executionRuntime.consumeVisualAttachment(res.callId)
                if (visual != null && context.provider.selectedModelInfo?.supportsVision == true) {
                    pendingVisualAttachments = pendingVisualAttachments + visual
                }
                state.workingHistory.add(
                    ChatMessage(
                        id = "tmp_tool_${res.callId}_${System.nanoTime()}",
                        conversationId = context.conversationId,
                        role = MessageRole.TOOL,
                        content = content,
                        createdAtMillis = System.currentTimeMillis(),
                        status = if (res.status == ToolExecutionStatus.SUCCEEDED) MessageStatus.COMPLETE else MessageStatus.FAILED,
                        toolCallId = res.callId,
                        toolName = res.toolName
                    )
                )
            }

            collector.send(
                AgentEvent.IntermediateMessagesProduced(
                    taskId = context.taskId,
                    assistantToolCalls = iterToolCalls,
                    assistantText = turnAssistantPreface,
                    toolResults = turnResults.toList()
                )
            )
            collector.send(
                AgentEvent.StatusChanged(
                    taskId = context.taskId,
                    status = AgentRunStatus.OBSERVING_RESULT,
                    message = "Observed ${turnResults.size} tool result(s)",
                    phase = AgentExecutionPhase.OBSERVING
                )
            )
        }

        val maxIterMsg = "Iteration limit reached (${context.limits.maxIterations} maximum reasoning iterations) without a final response."
        state.steps.add(
            ExecutionStep(
                id = "step_max_iter_${state.steps.size + 1}",
                title = "Iteration limit reached",
                detail = maxIterMsg,
                state = ExecutionStepState.FAILED,
                kind = StepKind.ERROR
            )
        )
        val verification = buildVerificationSummary(state, forceUnverified = true)
        val plan = buildPlanIfAny(state, verification)
        collector.send(
            AgentEvent.Failed(
                taskId = context.taskId,
                partialText = state.latestStreamingText,
                errorMessage = maxIterMsg,
                plan = plan,
                verification = verification,
                usage = state.totalUsage,
                taskSummary = buildTaskSummary(state, AgentTaskStatus.FAILED)
            )
        )
    }

    private suspend fun handleAmbiguityIfNeeded(
        collector: ProducerScope<AgentEvent>,
        context: AgentExecutionContext,
        control: AgentControlHandle,
        state: MutableRunState,
        stepIdx: Int?,
        call: ToolCall,
        result: ToolCallResult
    ): ToolCallResult {
        val parsed = runCatching { result.outputJson?.let { JSONObject(it) } }.getOrNull() ?: return result
        val status = parsed.optString("status")
        val errorCode = parsed.optString("errorCode")
        val isUiAmbiguity = status == "UI_AMBIGUOUS" || errorCode == "UI_AMBIGUOUS" || errorCode == "AMBIGUOUS_TARGET"
        val isAppAmbiguity = status == "AMBIGUOUS" || errorCode == "APP_AMBIGUOUS"
        if (!isUiAmbiguity && !isAppAmbiguity) return result

        val candidatesArr: JSONArray = parsed.optJSONArray("candidates") ?: return result
        val candidates = buildList {
            for (i in 0 until candidatesArr.length()) {
                val obj = candidatesArr.optJSONObject(i) ?: continue
                if (isUiAmbiguity) {
                    val nodeIndex = obj.optInt("node_index", -1)
                    if (nodeIndex >= 0) {
                        val label = obj.optString("label").ifBlank { "Element #$nodeIndex" }
                        val role = obj.optString("role").ifBlank { "element" }
                        val bounds = obj.optString("bounds").ifBlank { "" }
                        add(
                            AmbiguityCandidateItem(
                                displayName = label,
                                packageName = "node:$nodeIndex",
                                isLaunchable = false,
                                subtitle = listOfNotNull(role.takeIf { it.isNotBlank() }, bounds.takeIf { it.isNotBlank() }).joinToString(" · "),
                                nodeIndex = nodeIndex
                            )
                        )
                    }
                } else {
                    val pkg = obj.optString("package_name").ifBlank { obj.optString("packageName") }
                    val name = obj.optString("display_name").ifBlank { obj.optString("appName").ifBlank { pkg } }
                    if (pkg.isNotBlank()) {
                        add(
                            AmbiguityCandidateItem(
                                displayName = name,
                                packageName = pkg,
                                isLaunchable = obj.optBoolean("launchable", true)
                            )
                        )
                    }
                }
            }
        }
        if (candidates.isEmpty()) return result

        val origArgs = executionRuntime.parseArgsObject(call.argumentsJson)
        val query = parsed.optString("query").ifBlank {
            if (isUiAmbiguity) {
                origArgs?.optString("text")?.ifBlank { null }
                    ?: origArgs?.optString("content_description")?.ifBlank { null }
                    ?: "UI control"
            } else {
                "application"
            }
        }
        val reason = parsed.optString("reason").ifBlank {
            if (isUiAmbiguity) {
                "Multiple UI controls matched '$query'. Choose which element to interact with."
            } else {
                "Multiple installed apps matched '$query'. Choose one to continue."
            }
        }
        val request = AmbiguitySelectionRequest(
            selectionId = "amb_${UUID.randomUUID()}",
            taskId = context.taskId,
            query = query,
            reason = reason,
            candidates = candidates,
            isUiElementSelection = isUiAmbiguity
        )

        updateStep(state, stepIdx, ExecutionStepState.AWAITING_CONFIRMATION, reason, StepKind.APPROVAL)
        emitPlanSnapshot(collector, state)
        collector.send(
            AgentEvent.StatusChanged(
                taskId = context.taskId,
                status = AgentRunStatus.WAITING_FOR_AMBIGUITY_CHOICE,
                message = reason,
                phase = AgentExecutionPhase.WAITING_FOR_USER
            )
        )
        collector.send(AgentEvent.AmbiguityChoiceRequired(context.taskId, request))

        val selectedKey = try {
            state.isWaitingOnUser = true
            control.requestAmbiguityChoice(request)
        } finally {
            state.isWaitingOnUser = false
        }

        if (selectedKey.isNullOrBlank()) {
            return result
        }

        collector.send(AgentEvent.Resumed(context.taskId))
        if (isUiAmbiguity) {
            val chosenCandidate = candidates.firstOrNull { it.packageName == selectedKey || it.nodeIndex?.toString() == selectedKey }
            val chosenIndex = chosenCandidate?.nodeIndex
                ?: selectedKey.removePrefix("node:").toIntOrNull()
                ?: return result
            val snapshotId = parsed.optJSONObject("screen")?.optLong("snapshotId", -1L)?.takeIf { it > 0L }
            val nextArgs = JSONObject(origArgs?.toString() ?: "{}").apply {
                put("node_index", chosenIndex)
                if (snapshotId != null) put("snapshot_id", snapshotId)
                remove("view_id")
                remove("content_description")
                remove("hint_or_label")
                remove("anchor_context")
                remove("region")
                if (call.toolName !in setOf("type_text", "edit_text_field")) {
                    remove("text")
                }
            }
            updateStep(state, stepIdx, ExecutionStepState.RUNNING, "Running ${call.toolName} on chosen element #$chosenIndex...", StepKind.EXECUTED)
            emitPlanSnapshot(collector, state)
            return executionRuntime.executeToolCall(
                call = ToolCall(
                    id = call.id,
                    toolName = call.toolName,
                    argumentsJson = nextArgs.toString()
                ),
                settings = context.settings,
                timeoutMillis = context.limits.toolTimeoutMillis,
                maxResultChars = context.limits.maxToolResultChars
            )
        }

        return if (call.toolName == "launch_app") {
            updateStep(state, stepIdx, ExecutionStepState.RUNNING, "Launching selected app ($selectedKey)...", StepKind.EXECUTED)
            emitPlanSnapshot(collector, state)
            executionRuntime.executeToolCall(
                call = ToolCall(
                    id = call.id,
                    toolName = "launch_app",
                    argumentsJson = JSONObject().put("app_name", selectedKey).toString()
                ),
                settings = context.settings,
                timeoutMillis = context.limits.toolTimeoutMillis,
                maxResultChars = context.limits.maxToolResultChars
            )
        } else {
            val enriched = JSONObject(parsed.toString())
                .put("status", "FOUND")
                .put("selected_package", selectedKey)
                .put("package_name", selectedKey)
                .put("display_name", candidates.firstOrNull { it.packageName == selectedKey }?.displayName ?: selectedKey)
            result.copy(
                status = ToolExecutionStatus.SUCCEEDED,
                outputJson = enriched.toString(),
                displaySummary = "User selected $selectedKey",
                errorMessage = null
            )
        }
    }

    /**
     * Returns Pair(resolvedResult, wasWaitingForAppLock).
     */
    private suspend fun handleAppLockIfNeeded(
        collector: ProducerScope<AgentEvent>,
        context: AgentExecutionContext,
        control: AgentControlHandle,
        state: MutableRunState,
        stepIdx: Int?,
        call: ToolCall,
        result: ToolCallResult
    ): Pair<ToolCallResult, Boolean> {
        val parsed = runCatching { result.outputJson?.let { JSONObject(it) } }.getOrNull()
            ?: return result to false
        val status = parsed.optString("status")
        val errorCode = parsed.optString("errorCode")
        if (status != "BLOCKED_BY_APPLOCK" && errorCode != "BLOCKED_BY_APPLOCK") {
            return result to false
        }

        val targetPackage = parsed.optString("target_package").ifBlank { parsed.optString("packageName") }
        if (targetPackage.isBlank()) return result to false

        val appName = parsed.optString("display_name")
            .ifBlank { parsed.optString("appName") }
            .ifBlank { targetPackage }
        val foregroundPkg = parsed.optString("foreground_package")
            .ifBlank { parsed.optString("observed_foreground_package") }
            .ifBlank { "com.transsion.applock" }
        val title = parsed.optString("user_title").ifBlank { "Unlock $appName" }
        val message = parsed.optString("user_message").ifBlank {
            "Your device's AppLock is protecting this application. Unlock it on your phone. Nexa will continue automatically when access is available."
        }
        val timeoutSeconds = context.settings.appLockWaitTimeoutSeconds.coerceAtLeast(1)
        val timeoutMillis = timeoutSeconds * 1000L

        updateStep(
            state = state,
            index = stepIdx,
            newState = ExecutionStepState.AWAITING_CONFIRMATION,
            detail = "$title — waiting for user to unlock on device",
            kind = StepKind.APPROVAL
        )
        emitPlanSnapshot(collector, state)

        val waitRequest = UserUnlockWaitRequest(
            taskId = context.taskId,
            targetAppName = appName,
            targetPackage = targetPackage,
            foregroundPackage = foregroundPkg,
            title = title,
            message = message,
            timeoutSeconds = timeoutSeconds,
            remainingSeconds = timeoutSeconds,
            manualContinueRequired = false
        )
        collector.send(
            AgentEvent.StatusChanged(
                taskId = context.taskId,
                status = AgentRunStatus.WAITING_FOR_USER_UNLOCK,
                message = message,
                phase = AgentExecutionPhase.WAITING_FOR_USER
            )
        )
        collector.send(AgentEvent.WaitingForUserUnlock(context.taskId, waitRequest))

        val pollInterval = appLockPollIntervalMillis.coerceIn(25L, 1_000L)
        var unlocked = false

        try {
            state.isWaitingOnUser = true
            val startedWaitMs = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedWaitMs < timeoutMillis) {
                currentCoroutineContext().ensureActive()
                checkPause(collector, context.taskId, control)
                val fgNow = foregroundPackageProvider()
                if (fgNow == targetPackage) {
                    unlocked = true
                    break
                }
                withTimeoutOrNull(pollInterval) {
                    NexaAccessibilityService.windowChanges.first()
                }
                if (foregroundPackageProvider() == targetPackage) {
                    unlocked = true
                    break
                }
            }

            if (!unlocked) {
                val manualReason =
                    "AppLock wait timed out after ${timeoutSeconds}s while waiting for $appName to unlock. Unlock on your phone and tap Continue to resume this task."
                collector.send(
                    AgentEvent.UserUnlockTimeoutManualContinue(
                        taskId = context.taskId,
                        targetAppName = appName,
                        targetPackage = targetPackage,
                        reason = manualReason
                    )
                )
                val continued = control.awaitUserUnlockManualContinue(
                    targetAppName = appName,
                    targetPackage = targetPackage,
                    reason = manualReason
                )
                if (!continued) {
                    throw CancellationException("Task cancelled while waiting for AppLock unlock.")
                }
                unlocked = foregroundPackageProvider() == targetPackage
            }
        } finally {
            state.isWaitingOnUser = false
        }

        collector.send(AgentEvent.Resumed(context.taskId))

        if (call.toolName == "play_on_youtube") {
            val fgCheck = foregroundPackageProvider()
            if (unlocked || fgCheck == targetPackage || fgCheck != "com.transsion.applock") {
                updateStep(
                    state,
                    stepIdx,
                    ExecutionStepState.RUNNING,
                    "$appName unlocked — resuming YouTube search and playback...",
                    StepKind.EXECUTED
                )
                emitPlanSnapshot(collector, state)
                collector.send(
                    AgentEvent.StatusChanged(
                        context.taskId,
                        AgentRunStatus.EXECUTING_TOOL,
                        "Resuming play_on_youtube after unlock..."
                    )
                )
                val resumedResult = executionRuntime.executeToolCall(
                    call = call,
                    settings = context.settings,
                    timeoutMillis = context.limits.toolTimeoutMillis,
                    maxResultChars = context.limits.maxToolResultChars
                )
                return resumedResult to true
            }
        }

        val finalFg = foregroundPackageProvider() ?: if (unlocked) targetPackage else foregroundPkg
        if (unlocked || finalFg == targetPackage) {
            val verifiedObj = JSONObject(parsed.toString())
                .put("status", "FOREGROUND_VERIFIED")
                .put("success", true)
                .put("foreground_verified", true)
                .put("unlock_verified", true)
                .put("requires_user_action", false)
                .put("observed_foreground_package", targetPackage)
                .put("foreground_package", targetPackage)
                .put("message", "$appName was unlocked by the user and verified in the foreground ($targetPackage).")
            verifiedObj.remove("errorCode")
            val verifiedJson = verifiedObj.toString()
            return result.copy(
                status = ToolExecutionStatus.SUCCEEDED,
                outputJson = verifiedJson,
                displaySummary = "$appName unlocked & verified in foreground",
                errorMessage = null
            ) to true
        }

        // User tapped Continue, but foreground was not observed as targetPackage (honest report).
        val unverifiedJson = JSONObject(parsed.toString())
            .put("user_continued", true)
            .put("observed_foreground_package", finalFg)
            .put("foreground_package", finalFg)
            .put("foreground_verified", false)
            .put("message", "User tapped Continue for $appName, but current foreground package is '$finalFg'.")
            .toString()
        return result.copy(
            status = ToolExecutionStatus.FAILED,
            outputJson = unverifiedJson,
            displaySummary = "$appName still blocked or unverified (foreground: $finalFg)",
            errorMessage = "Expected '$targetPackage' after unlock, observed '$finalFg'."
        ) to true
    }

    private suspend fun checkPause(
        collector: ProducerScope<AgentEvent>,
        taskId: String,
        control: AgentControlHandle
    ) {
        if (control.isPausedNow()) {
            collector.send(AgentEvent.Paused(taskId, "Execution paused by user"))
            collector.send(AgentEvent.StatusChanged(taskId, AgentRunStatus.PAUSED, "Execution paused by user"))
            control.awaitIfPaused()
            collector.send(AgentEvent.Resumed(taskId))
        }
    }

    private suspend fun emitPlanSnapshot(
        collector: ProducerScope<AgentEvent>,
        state: MutableRunState
    ) {
        val plan = buildPlanIfAny(state, buildVerificationSummary(state)) ?: return
        collector.send(AgentEvent.PlanUpdated(state.taskId, plan))
    }

    private fun updateStep(
        state: MutableRunState,
        index: Int?,
        newState: ExecutionStepState,
        detail: String,
        kind: StepKind
    ) {
        if (index == null || index !in state.steps.indices) return
        val old = state.steps[index]
        state.steps[index] = old.copy(state = newState, detail = detail, kind = kind)
    }

    private fun previewCallArguments(call: ToolCall, context: AgentExecutionContext): String {
        val tool = toolRegistry.findExecutableTool(call.toolName)
        val risk = tool?.definition?.riskCategory
        val shouldRedact = context.settings.redactSensitiveStepsInLogs && (
            risk == com.example.domain.model.ToolRiskCategory.SENSITIVE ||
                risk == com.example.domain.model.ToolRiskCategory.EXTERNAL_SIDE_EFFECT ||
                call.toolName in setOf("type_text", "edit_text_field")
            )
        if (shouldRedact) {
            return "Arguments redacted by privacy policy"
        }
        val parsed = executionRuntime.parseArgsObject(call.argumentsJson)
        if (tool != null && parsed != null) {
            val described = runCatching { tool.describeAction(parsed) }.getOrNull()?.trim()
            if (!described.isNullOrEmpty()) return described.take(140)
        }
        val compact = call.argumentsJson.trim().take(120)
        return if (compact.isBlank() || compact == "{}") "No arguments" else "Args: $compact"
    }

    private fun canonicalizeJson(raw: String): String {
        val parsed = executionRuntime.parseArgsObject(raw) ?: return raw.trim()
        val sorted = JSONObject()
        parsed.keys().asSequence().sorted().forEach { key ->
            sorted.put(key, parsed.opt(key))
        }
        return sorted.toString()
    }

    private fun mergeUsage(first: TokenUsage?, second: TokenUsage?): TokenUsage? {
        if (first == null) return second
        if (second == null) return first
        val prompt = listOfNotNull(first.promptTokens, second.promptTokens).takeIf { it.isNotEmpty() }?.sum()
        val completion = listOfNotNull(first.completionTokens, second.completionTokens).takeIf { it.isNotEmpty() }?.sum()
        val total = listOfNotNull(first.totalTokens, second.totalTokens).takeIf { it.isNotEmpty() }?.sum()
            ?: if (prompt != null && completion != null) prompt + completion else null
        return TokenUsage(prompt, completion, total)
    }

    private fun buildVerificationSummary(
        state: MutableRunState,
        forceUnverified: Boolean = false
    ): TaskVerificationSummary {
        val results = state.allResults
        val succeeded = results.count { it.status == ToolExecutionStatus.SUCCEEDED }
        val failed = results.count { it.status == ToolExecutionStatus.FAILED }
        val rejected = results.count { it.status == ToolExecutionStatus.REJECTED }
        val timedOut = results.count { it.status == ToolExecutionStatus.TIMED_OUT }
        val cancelled = results.count { it.status == ToolExecutionStatus.CANCELLED }
        val executed = results.count {
            it.status == ToolExecutionStatus.SUCCEEDED ||
                it.status == ToolExecutionStatus.FAILED ||
                it.status == ToolExecutionStatus.TIMED_OUT
        }

        var verifiedCompleted = 0
        var unverifiedCompleted = 0
        var blockedCount = 0
        var observedMaxLevel = 0

        results.forEach { res ->
            val obj = runCatching { res.outputJson?.let { JSONObject(it) } }.getOrNull()
            val statusTag = obj?.optString("status").orEmpty()
            val errorCode = obj?.optString("errorCode").orEmpty()
            val levelNum = if (obj != null && obj.has("verificationLevel")) {
                obj.optInt("verificationLevel", -1)
            } else {
                -1
            }
            if (levelNum > observedMaxLevel) {
                observedMaxLevel = levelNum
            }
            val isBlocked = statusTag in setOf("BLOCKED_BY_APPLOCK", "BLOCKED_BY_DIALOG", "APP_LOADING") ||
                errorCode in setOf("BLOCKED_BY_APPLOCK", "BLOCKED_BY_DIALOG", "APP_LOADING")
            if (isBlocked) {
                blockedCount++
            }
            if (res.status == ToolExecutionStatus.SUCCEEDED) {
                val isUnverifiedSuccess = when {
                    res.toolName == "launch_app" && statusTag == "LAUNCH_DISPATCHED" -> true
                    res.toolName == "open_url" && statusTag == "LAUNCH_DISPATCHED" -> true
                    res.toolName == "play_on_youtube" && statusTag in setOf("SEARCH_OPENED", "PLAYBACK_UNVERIFIED") -> true
                    levelNum == 0 -> true
                    obj?.has("foreground_verified") == true &&
                        !obj.optBoolean("foreground_verified", false) &&
                        res.toolName in setOf("launch_app", "open_url") -> true
                    else -> false
                }
                if (isUnverifiedSuccess) {
                    unverifiedCompleted++
                } else {
                    verifiedCompleted++
                }
            }
        }

        val totalSteps = state.steps.size
        val remainingSteps = state.steps.count {
            it.state == ExecutionStepState.PENDING ||
                it.state == ExecutionStepState.RUNNING ||
                it.state == ExecutionStepState.AWAITING_CONFIRMATION
        }

        val latestYouTubeResult = results.lastOrNull { it.toolName == "play_on_youtube" }
        val youtubeObjectiveUnverified = latestYouTubeResult != null && run {
            val ytStatus = runCatching {
                latestYouTubeResult.outputJson?.let { JSONObject(it).optString("status") }
            }.getOrNull()
            ytStatus != "PLAYBACK_STARTED"
        }

        val lastActionFailed = results.lastOrNull()?.let { it.status != ToolExecutionStatus.SUCCEEDED } == true

        val objectiveVerified = when {
            forceUnverified -> false
            state.requestedToolCalls == 0 -> true
            blockedCount > 0 -> false
            youtubeObjectiveUnverified -> false
            lastActionFailed -> false
            remainingSteps > 0 -> false
            else -> verifiedCompleted > 0 || (succeeded > 0 && failed == 0)
        }

        val verified = when {
            forceUnverified -> false
            state.requestedToolCalls == 0 -> true
            else -> succeeded > 0 &&
                failed == 0 &&
                rejected == 0 &&
                timedOut == 0 &&
                cancelled == 0 &&
                blockedCount == 0 &&
                unverifiedCompleted == 0
        }

        val computedMaxLevel = when {
            observedMaxLevel > 0 -> observedMaxLevel
            verified && objectiveVerified -> 4
            verifiedCompleted > 0 -> 2
            else -> 0
        }

        val note = when {
            state.requestedToolCalls == 0 -> "Conversational response (no tool calls)."
            verified && objectiveVerified ->
                "Verified $verifiedCompleted/${state.requestedToolCalls} tool call(s) completed."
            else -> buildString {
                append("$verifiedCompleted verified")
                if (unverifiedCompleted > 0) append(", $unverifiedCompleted unverified")
                if (failed > 0) append(", $failed failed")
                if (blockedCount > 0) append(", $blockedCount blocked")
                if (rejected > 0) append(", $rejected rejected")
                if (timedOut > 0) append(", $timedOut timed out")
                if (cancelled > 0) append(", $cancelled cancelled")
                if (remainingSteps > 0) append(", $remainingSteps remaining")
                append(".")
            }
        }
        return TaskVerificationSummary(
            requestedToolCalls = state.requestedToolCalls,
            executedToolCalls = executed,
            succeededCount = succeeded,
            failedCount = failed,
            rejectedCount = rejected,
            timedOutCount = timedOut,
            cancelledCount = cancelled,
            isVerified = verified,
            summaryNote = note,
            totalSteps = totalSteps,
            verifiedCompletedCount = verifiedCompleted,
            unverifiedCompletedCount = unverifiedCompleted,
            blockedCount = blockedCount,
            remainingCount = remainingSteps,
            objectiveVerified = objectiveVerified,
            maxVerificationLevel = computedMaxLevel
        )
    }

    private fun statusForTool(toolName: String): AgentRunStatus = when (toolName) {
        "observe_screen", "take_screenshot" -> AgentRunStatus.OBSERVING_RESULT
        "tap_element", "double_tap_element", "long_press_element", "type_text", "edit_text_field" ->
            AgentRunStatus.LOCATING_TARGET
        else -> AgentRunStatus.EXECUTING_TOOL
    }

    private fun phaseForTool(toolName: String): AgentExecutionPhase = when (toolName) {
        "observe_screen", "take_screenshot" -> AgentExecutionPhase.OBSERVING
        "wait_for_screen_change", "wait_for_element" -> AgentExecutionPhase.WAITING_FOR_TRANSITION
        "tap_element", "double_tap_element", "long_press_element", "type_text", "edit_text_field" ->
            AgentExecutionPhase.LOCATING_TARGET
        else -> AgentExecutionPhase.EXECUTING_ACTION
    }

    private suspend fun emitSubgoalProgress(
        collector: ProducerScope<AgentEvent>,
        taskId: String,
        goal: String,
        call: ToolCall,
        result: ToolCallResult
    ) {
        val parsed = runCatching { result.outputJson?.let { JSONObject(it) } }.getOrNull()
        val vLevel = if (parsed != null && parsed.has("verificationLevel")) {
            VerificationLevel.entries.firstOrNull { it.levelNumber == parsed.optInt("verificationLevel", -1) }
        } else {
            null
        }
        val fCat = parsed?.optString("failureCategory")?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { ComputerUseFailureCategory.valueOf(raw) }.getOrNull()
        }
        val suggested = parsed?.optString("suggestedRecovery")?.ifBlank { null }
        val verifiedLabel = if (result.status == ToolExecutionStatus.SUCCEEDED && (vLevel == null || vLevel.levelNumber >= 1)) {
            "${call.toolName}: ${result.displaySummary}".take(96)
        } else {
            null
        }
        collector.send(
            AgentEvent.SubgoalProgressUpdated(
                taskId = taskId,
                subgoal = goal.take(80),
                lastVerifiedAction = verifiedLabel,
                nextPlannedAction = suggested?.take(96),
                verificationLevel = vLevel,
                failureCategory = fCat
            )
        )
    }

    /**
     * Condenses large UI node arrays in older TOOL messages so multi-step reasoning stays within
     * context budgets while preserving the most recent screen state verbatim.
     */
    private fun condenseOlderScreenPayloads(workingHistory: MutableList<ChatMessage>) {
        for (i in workingHistory.indices) {
            val msg = workingHistory[i]
            if (msg.role != MessageRole.TOOL) continue
            val obj = runCatching { JSONObject(msg.content) }.getOrNull() ?: continue
            val screen = obj.optJSONObject("screen")
                ?: obj.takeIf { it.has("nodes") && it.has("snapshotId") }
                ?: continue
            if (!screen.has("nodes")) continue
            val nodeCount = screen.optJSONArray("nodes")?.length() ?: 0
            screen.remove("nodes")
            screen.put("nodesCondensed", true)
            screen.put("condensedNodeCount", nodeCount)
            workingHistory[i] = msg.copy(content = obj.toString())
        }
    }

    private fun buildPlanIfAny(
        state: MutableRunState,
        verification: TaskVerificationSummary
    ): ExecutionPlan? {
        if (state.steps.isEmpty()) return null
        return ExecutionPlan(
            goal = state.goal,
            isPreview = false,
            steps = state.steps.toList(),
            verificationSummary = verification
        )
    }

    private fun buildTaskSummary(
        state: MutableRunState,
        status: AgentTaskStatus
    ): TaskSummary? {
        if (state.steps.isEmpty()) return null
        val completed = state.steps.count { it.state == ExecutionStepState.COMPLETED }
        return TaskSummary(
            taskId = state.taskId,
            title = state.goal.take(64),
            status = status,
            completedSteps = completed,
            totalSteps = state.steps.size,
            targetApp = "Nexa Tools",
            isSampleData = false
        )
    }

    private data class MutableRunState(
        val taskId: String,
        val goal: String,
        val workingHistory: MutableList<ChatMessage>,
        val steps: MutableList<ExecutionStep> = mutableListOf(),
        val allResults: MutableList<ToolCallResult> = mutableListOf(),
        val failedSignatureCounts: MutableMap<String, Int> = mutableMapOf(),
        var requestedToolCalls: Int = 0,
        var totalToolCalls: Int = 0,
        var totalUsage: TokenUsage? = null,
        var latestStreamingText: String = "",
        @Volatile var isWaitingOnUser: Boolean = false
    )
}
