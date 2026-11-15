package com.example.domain.agent

import com.example.data.repository.ConnectionsRepository
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentExecutionContext
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ChatMessage
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
import com.example.domain.provider.ProviderRequest
import com.example.domain.provider.ProviderStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.launch
import org.json.JSONObject
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
    private val diagnosticSink: ((ToolDispatchDiagnostic) -> Unit)? = null
) : AutonomousAgentOrchestrator {

    constructor(
        connectionsRepository: ConnectionsRepository,
        toolRegistry: ToolRegistry,
        safetyPolicyManager: SafetyPolicyManager,
        executionRuntime: ToolExecutionRuntime,
        contextBuilder: ConversationContextBuilder = ConversationContextBuilder(),
        diagnosticSink: ((ToolDispatchDiagnostic) -> Unit)? = null
    ) : this(
        streamCaller = ProviderStreamCaller { provider, request ->
            connectionsRepository.streamChat(provider, request)
        },
        toolRegistry = toolRegistry,
        safetyPolicyManager = safetyPolicyManager,
        executionRuntime = executionRuntime,
        contextBuilder = contextBuilder,
        diagnosticSink = diagnosticSink
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

        send(AgentEvent.StatusChanged(context.taskId, AgentRunStatus.PREPARING, "Preparing execution context..."))

        val timedOut = AtomicBoolean(false)
        var finishedNormally = false
        coroutineScope {
            val worker = launch {
                executeLoop(this@channelFlow, context, attachments, control, state)
                finishedNormally = true
            }
            val watchdog = launch(timeoutDispatcher) {
                delay(context.limits.taskTimeoutMillis)
                if (!finishedNormally) {
                    timedOut.set(true)
                    worker.cancel(CancellationException("Task timed out after ${context.limits.taskTimeoutMillis}ms"))
                }
            }
            worker.join()
            watchdog.cancel()
        }

        if (timedOut.get()) {
            currentCoroutineContext().ensureActive()
            val timeoutMsg = "Task timed out after ${context.limits.taskTimeoutMillis / 1000L}s before completion."
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
            val thinkingMessage = if (iteration == 1) {
                "Thinking with ${modelId.substringAfterLast("/")}..."
            } else {
                "Observing tool results (step $iteration)..."
            }
            collector.send(AgentEvent.StatusChanged(context.taskId, thinkingStatus, thinkingMessage))

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

                collector.send(AgentEvent.StatusChanged(context.taskId, AgentRunStatus.VERIFYING, "Verifying completion..."))
                if (state.requestedToolCalls > 0) {
                    val interimVerification = buildVerificationSummary(state)
                    state.steps.add(
                        ExecutionStep(
                            id = "step_verify_${state.steps.size + 1}",
                            title = if (interimVerification.isVerified) "Task verification passed" else "Task finished with tool issues",
                            detail = interimVerification.summaryNote,
                            state = if (interimVerification.isVerified) ExecutionStepState.COMPLETED else ExecutionStepState.FAILED,
                            kind = StepKind.VERIFICATION
                        )
                    )
                }
                val verification = buildVerificationSummary(state)
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
                        taskSummary = buildTaskSummary(state, AgentTaskStatus.COMPLETED)
                    )
                )
                return
            }

            // Tool calls requested in this iteration
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

                val result = if (tool == null || !tool.definition.isImplemented || parsedArgs == null || preValidationError != null) {
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
                                    context.taskId,
                                    AgentRunStatus.AWAITING_APPROVAL,
                                    "Approval needed for ${call.toolName}"
                                )
                            )
                            collector.send(AgentEvent.ApprovalRequired(context.taskId, policy.request))

                            val decision = control.requestApproval(policy.request)
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
                                        context.taskId,
                                        AgentRunStatus.EXECUTING_TOOL,
                                        "Executing ${call.toolName}..."
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
                                    context.taskId,
                                    AgentRunStatus.EXECUTING_TOOL,
                                    "Executing ${call.toolName}..."
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

                if (result.status != ToolExecutionStatus.SUCCEEDED) {
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
                emitPlanSnapshot(collector, state)
            }

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
                    context.taskId,
                    AgentRunStatus.OBSERVING_RESULT,
                    "Observed ${turnResults.size} tool result(s)"
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
        if (context.settings.redactSensitiveStepsInLogs &&
            tool?.definition?.riskCategory == com.example.domain.model.ToolRiskCategory.SENSITIVE
        ) {
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
        val verified = when {
            forceUnverified -> false
            state.requestedToolCalls == 0 -> true
            else -> succeeded > 0 && failed == 0 && rejected == 0 && timedOut == 0 && cancelled == 0
        }
        val note = when {
            state.requestedToolCalls == 0 -> "Conversational response (no tool calls)."
            verified -> "Verified $succeeded/${state.requestedToolCalls} tool call(s) succeeded."
            else -> buildString {
                append("$succeeded succeeded")
                if (failed > 0) append(", $failed failed")
                if (rejected > 0) append(", $rejected rejected")
                if (timedOut > 0) append(", $timedOut timed out")
                if (cancelled > 0) append(", $cancelled cancelled")
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
            summaryNote = note
        )
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
        var latestStreamingText: String = ""
    )
}
