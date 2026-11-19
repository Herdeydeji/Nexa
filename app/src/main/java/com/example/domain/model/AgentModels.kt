package com.example.domain.model

import com.squareup.moshi.JsonClass

enum class AgentStatus(val label: String) {
    IDLE("Idle"),
    PREVIEW("Preview"),
    THINKING("Thinking"),
    EXECUTING("Executing"),
    AWAITING_CONFIRMATION("Awaiting Approval"),
    WAITING_FOR_USER("Waiting for User"),
    PAUSED("Paused"),
    OFFLINE("No Provider")
}

enum class ExecutionStepState(val label: String) {
    PENDING("Pending"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    AWAITING_CONFIRMATION("Awaiting confirmation"),
    CANCELLED("Cancelled")
}

enum class StepKind {
    INTENT,
    EXECUTED,
    RESULT,
    APPROVAL,
    VERIFICATION,
    ERROR,
    CANCELLED
}

@JsonClass(generateAdapter = true)
data class ExecutionStep(
    val id: String,
    val title: String,
    val detail: String,
    val state: ExecutionStepState,
    val toolName: String? = null,
    val timestampLabel: String? = null,
    val kind: StepKind = StepKind.EXECUTED
)

@JsonClass(generateAdapter = true)
data class ExecutionPlan(
    val goal: String,
    val isPreview: Boolean = true,
    val steps: List<ExecutionStep>,
    val verificationSummary: TaskVerificationSummary? = null
)

@JsonClass(generateAdapter = true)
data class TaskSummary(
    val taskId: String,
    val title: String,
    val status: AgentTaskStatus,
    val completedSteps: Int,
    val totalSteps: Int,
    val targetApp: String? = null,
    val isSampleData: Boolean = true
)

enum class AgentTaskStatus(val label: String) {
    RUNNING("Running"),
    AWAITING_APPROVAL("Awaiting Approval"),
    WAITING_FOR_USER_UNLOCK("Waiting for Unlock"),
    WAITING_FOR_PERMISSION("Waiting for Permission"),
    WAITING_FOR_EXTERNAL_APP("In External App"),
    VERIFYING("Verifying"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    PARTIALLY_COMPLETED("Partially Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

@JsonClass(generateAdapter = true)
data class ResumableTaskSnapshot(
    val taskId: String,
    val conversationId: String,
    val prompt: String,
    val targetAppName: String? = null,
    val targetPackage: String? = null,
    val blockedToolName: String? = null,
    val blockedArgumentsJson: String? = null,
    val reason: String,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class AmbiguityCandidateItem(
    val displayName: String,
    val packageName: String,
    val isLaunchable: Boolean = true,
    val subtitle: String? = null,
    val nodeIndex: Int? = null
)

data class AmbiguitySelectionRequest(
    val selectionId: String,
    val taskId: String,
    val query: String,
    val reason: String,
    val candidates: List<AmbiguityCandidateItem>,
    val isUiElementSelection: Boolean = false
)

data class UserUnlockWaitRequest(
    val taskId: String,
    val targetAppName: String,
    val targetPackage: String,
    val foregroundPackage: String,
    val title: String,
    val message: String,
    val timeoutSeconds: Int = 180,
    val remainingSeconds: Int = 180,
    val manualContinueRequired: Boolean = false
)

data class AgentTask(
    val id: String,
    val title: String,
    val prompt: String,
    val status: AgentTaskStatus,
    val isSampleData: Boolean,
    val providerLabel: String,
    val modelLabel: String,
    val targetApp: String?,
    val requiredCapabilities: List<String>,
    val steps: List<ExecutionStep>,
    val failureReason: String? = null,
    val summaryMessage: String? = null,
    val verificationSummary: TaskVerificationSummary? = null,
    val resumableSnapshot: ResumableTaskSnapshot? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

enum class ToolRiskCategory(val label: String) {
    READ_ONLY("Read-only"),
    REVERSIBLE("Reversible"),
    SENSITIVE("Sensitive"),
    DESTRUCTIVE("Destructive"),
    EXTERNAL_SIDE_EFFECT("External side effect")
}

enum class ToolParameterType(val jsonType: String) {
    STRING("string"),
    NUMBER("number"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    ARRAY("array"),
    OBJECT("object")
}

data class ToolParameter(
    val name: String,
    val type: ToolParameterType,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String> = emptyList()
)

data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
    val riskCategory: ToolRiskCategory = ToolRiskCategory.READ_ONLY,
    val requiredPermissions: List<String> = emptyList(),
    val resultSchemaDescription: String = "",
    val timeoutMillis: Long = 10_000L,
    val isImplemented: Boolean = true
) {
    val requiredParameterNames: List<String>
        get() = parameters.filter { it.required }.map { it.name }
}

@JsonClass(generateAdapter = true)
data class ToolCall(
    val id: String,
    val toolName: String,
    val argumentsJson: String
)

enum class ToolExecutionStatus(val label: String) {
    PENDING("Pending"),
    AWAITING_APPROVAL("Awaiting Approval"),
    RUNNING("Running"),
    SUCCEEDED("Succeeded"),
    FAILED("Failed"),
    REJECTED("Rejected"),
    TIMED_OUT("Timed Out"),
    CANCELLED("Cancelled")
}

@JsonClass(generateAdapter = true)
data class ToolCallResult(
    val callId: String,
    val toolName: String,
    val status: ToolExecutionStatus,
    val outputJson: String? = null,
    val displaySummary: String,
    val errorMessage: String? = null,
    val durationMillis: Long = 0L,
    val wasTruncated: Boolean = false
)

enum class AgentRunStatus(val label: String) {
    IDLE("Idle"),
    PREPARING("Preparing"),
    THINKING("Thinking"),
    LOCATING_TARGET("Locating Target"),
    SELECTING_TOOL("Selecting Tool"),
    AWAITING_APPROVAL("Awaiting Approval"),
    WAITING_FOR_USER_UNLOCK("Waiting for Unlock"),
    WAITING_FOR_PERMISSION("Waiting for Permission"),
    WAITING_FOR_EXTERNAL_APP("In External App"),
    WAITING_FOR_AMBIGUITY_CHOICE("Choose Option"),
    EXECUTING_TOOL("Executing Tool"),
    OBSERVING_RESULT("Observing Result"),
    REPLANNING("Replanning"),
    RECOVERING("Recovering"),
    VERIFYING("Verifying"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    PARTIALLY_COMPLETED("Partially Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

data class AgentTurn(
    val iterationIndex: Int,
    val assistantText: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolCallResult> = emptyList(),
    val usage: TokenUsage? = null
)

data class AgentExecutionLimits(
    val maxIterations: Int = 8,
    val maxToolCalls: Int = 12,
    val toolTimeoutMillis: Long = 10_000L,
    val taskTimeoutMillis: Long = 120_000L,
    val maxToolResultChars: Int = 8_000,
    val maxContextTokens: Int? = null,
    val maxRepeatedIdenticalFailures: Int = 2,
    val noProgressRetryLimit: Int = 3
)

data class AgentExecutionContext(
    val taskId: String,
    val conversationId: String,
    val userPrompt: String,
    val provider: ProviderConfig,
    val settings: AppSettings,
    val limits: AgentExecutionLimits,
    val availableTools: List<ToolDefinition>,
    val systemPrompt: String
)

data class ToolApprovalRequest(
    val approvalId: String,
    val taskId: String,
    val callId: String,
    val toolId: String,
    val toolName: String,
    val riskCategory: ToolRiskCategory,
    val intentSummary: String,
    val targetDataSummary: String,
    val isReversible: Boolean,
    val approvalEffect: String,
    val argumentsPreview: String
)

enum class ApprovalDecision {
    APPROVE,
    REJECT
}

@JsonClass(generateAdapter = true)
data class TaskVerificationSummary(
    val requestedToolCalls: Int = 0,
    val executedToolCalls: Int = 0,
    val succeededCount: Int = 0,
    val failedCount: Int = 0,
    val rejectedCount: Int = 0,
    val timedOutCount: Int = 0,
    val cancelledCount: Int = 0,
    val isVerified: Boolean = true,
    val summaryNote: String = "",
    val totalSteps: Int = 0,
    val verifiedCompletedCount: Int = 0,
    val unverifiedCompletedCount: Int = 0,
    val blockedCount: Int = 0,
    val remainingCount: Int = 0,
    val objectiveVerified: Boolean = isVerified,
    val maxVerificationLevel: Int = if (objectiveVerified && isVerified) 4 else if (verifiedCompletedCount > 0) 2 else 0
)

sealed interface AgentEvent {
    val taskId: String

    data class StatusChanged(
        override val taskId: String,
        val status: AgentRunStatus,
        val message: String,
        val phase: AgentExecutionPhase? = null,
        val currentSubgoal: String? = null,
        val nextPlannedAction: String? = null
    ) : AgentEvent

    data class TextDelta(
        override val taskId: String,
        val accumulatedText: String
    ) : AgentEvent

    data class SubgoalProgressUpdated(
        override val taskId: String,
        val subgoal: String,
        val lastVerifiedAction: String? = null,
        val nextPlannedAction: String? = null,
        val verificationLevel: VerificationLevel? = null,
        val failureCategory: ComputerUseFailureCategory? = null
    ) : AgentEvent

    data class ToolSelected(
        override val taskId: String,
        val iteration: Int,
        val toolCalls: List<ToolCall>
    ) : AgentEvent

    data class ApprovalRequired(
        override val taskId: String,
        val request: ToolApprovalRequest
    ) : AgentEvent

    data class ToolStarted(
        override val taskId: String,
        val call: ToolCall
    ) : AgentEvent

    data class ToolCompleted(
        override val taskId: String,
        val result: ToolCallResult
    ) : AgentEvent

    data class PlanUpdated(
        override val taskId: String,
        val plan: ExecutionPlan
    ) : AgentEvent

    data class Paused(
        override val taskId: String,
        val reason: String
    ) : AgentEvent

    data class Resumed(
        override val taskId: String
    ) : AgentEvent

    data class WaitingForUserUnlock(
        override val taskId: String,
        val request: UserUnlockWaitRequest
    ) : AgentEvent

    data class UserUnlockTimeoutManualContinue(
        override val taskId: String,
        val targetAppName: String,
        val targetPackage: String,
        val reason: String
    ) : AgentEvent

    data class AmbiguityChoiceRequired(
        override val taskId: String,
        val request: AmbiguitySelectionRequest
    ) : AgentEvent

    data class IntermediateMessagesProduced(
        override val taskId: String,
        val assistantToolCalls: List<ToolCall>,
        val assistantText: String,
        val toolResults: List<ToolCallResult>
    ) : AgentEvent

    data class Completed(
        override val taskId: String,
        val finalText: String,
        val plan: ExecutionPlan?,
        val verification: TaskVerificationSummary,
        val usage: TokenUsage?,
        val taskSummary: TaskSummary?,
        val partiallyCompleted: Boolean = false
    ) : AgentEvent

    data class Failed(
        override val taskId: String,
        val partialText: String,
        val errorMessage: String,
        val plan: ExecutionPlan?,
        val verification: TaskVerificationSummary,
        val usage: TokenUsage?,
        val taskSummary: TaskSummary?
    ) : AgentEvent

    data class Cancelled(
        override val taskId: String,
        val partialText: String,
        val plan: ExecutionPlan?,
        val verification: TaskVerificationSummary,
        val usage: TokenUsage?,
        val taskSummary: TaskSummary?
    ) : AgentEvent
}
