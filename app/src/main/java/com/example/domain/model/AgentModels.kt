package com.example.domain.model

import com.squareup.moshi.JsonClass

enum class AgentStatus(val label: String) {
    IDLE("Idle"),
    PREVIEW("Preview"),
    THINKING("Thinking"),
    EXECUTING("Executing"),
    AWAITING_CONFIRMATION("Awaiting Approval"),
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

@JsonClass(generateAdapter = true)
data class ExecutionStep(
    val id: String,
    val title: String,
    val detail: String,
    val state: ExecutionStepState,
    val toolName: String? = null,
    val timestampLabel: String? = null
)

@JsonClass(generateAdapter = true)
data class ExecutionPlan(
    val goal: String,
    val isPreview: Boolean = true,
    val steps: List<ExecutionStep>
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
    COMPLETED("Completed"),
    FAILED("Failed")
}

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
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)
