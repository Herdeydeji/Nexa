package com.example.domain.agent

import com.example.domain.model.AppSettings
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.ToolApprovalRequest
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolRiskCategory
import org.json.JSONObject
import java.util.UUID

sealed interface SafetyDecision {
    data object Allow : SafetyDecision
    data class RequireUserApproval(val reason: String) : SafetyDecision
    data class Deny(val reason: String) : SafetyDecision
}

sealed interface ToolPolicyEvaluation {
    data object Allow : ToolPolicyEvaluation
    data class RequireUserApproval(
        val request: ToolApprovalRequest,
        val reason: String
    ) : ToolPolicyEvaluation
    data class Deny(val reason: String) : ToolPolicyEvaluation
}

interface SafetyPolicyManager {
    fun evaluateTool(tool: AgentTool, settings: AppSettings): SafetyDecision

    fun evaluateToolCall(
        taskId: String,
        tool: ExecutableTool,
        call: ToolCall,
        args: JSONObject,
        settings: AppSettings
    ): ToolPolicyEvaluation
}

class DefaultSafetyPolicyManager : SafetyPolicyManager {
    override fun evaluateTool(tool: AgentTool, settings: AppSettings): SafetyDecision {
        if (tool.riskLevel == ToolRiskLevel.HIGH && settings.requireApprovalForExternalComms) {
            return SafetyDecision.RequireUserApproval(
                "${tool.name} accesses sensitive communication capabilities and requires confirmation."
            )
        }
        return when (settings.confirmationMode) {
            ConfirmationMode.ALWAYS_CONFIRM -> SafetyDecision.RequireUserApproval(
                "Confirmation mode is set to always ask before tool execution."
            )
            ConfirmationMode.SENSITIVE_ONLY -> {
                if (tool.riskLevel == ToolRiskLevel.HIGH || tool.riskLevel == ToolRiskLevel.MEDIUM) {
                    SafetyDecision.RequireUserApproval(
                        "${tool.name} modifies UI or system state and requires approval."
                    )
                } else {
                    SafetyDecision.Allow
                }
            }
            ConfirmationMode.SUPERVISED_AUTO -> SafetyDecision.Allow
        }
    }

    override fun evaluateToolCall(
        taskId: String,
        tool: ExecutableTool,
        call: ToolCall,
        args: JSONObject,
        settings: AppSettings
    ): ToolPolicyEvaluation {
        val def = tool.definition
        val category = def.riskCategory
        val mustConfirm = when (category) {
            // Non-bypassable gates: sensitive, destructive, and external side-effect tools
            // always require explicit human approval regardless of model prompt or auto mode.
            ToolRiskCategory.SENSITIVE,
            ToolRiskCategory.DESTRUCTIVE,
            ToolRiskCategory.EXTERNAL_SIDE_EFFECT -> true
            ToolRiskCategory.REVERSIBLE -> settings.confirmationMode != ConfirmationMode.SUPERVISED_AUTO
            ToolRiskCategory.READ_ONLY -> settings.confirmationMode == ConfirmationMode.ALWAYS_CONFIRM
        }

        if (!mustConfirm) {
            return ToolPolicyEvaluation.Allow
        }

        val actionSummary = tool.describeAction(args)
        val isReversible = category == ToolRiskCategory.READ_ONLY || category == ToolRiskCategory.REVERSIBLE
        val approvalEffect = when (category) {
            ToolRiskCategory.READ_ONLY -> "Allows Nexa to execute '${def.name}' and return the read-only result to the model."
            ToolRiskCategory.REVERSIBLE -> "Allows Nexa to run this reversible action and report the observed result."
            ToolRiskCategory.SENSITIVE -> "Grants explicit one-time approval for sensitive tool '${def.name}'."
            ToolRiskCategory.DESTRUCTIVE -> "Grants explicit one-time approval for this potentially irreversible action."
            ToolRiskCategory.EXTERNAL_SIDE_EFFECT -> "Permits an external side-effect action via '${def.name}'."
        }
        val reason = "${def.name} is classified as ${category.label} and requires explicit confirmation."

        val previewArgs = if (settings.redactSensitiveStepsInLogs && category == ToolRiskCategory.SENSITIVE) {
            "[Redacted in preview logs]"
        } else {
            args.toString()
        }

        val request = ToolApprovalRequest(
            approvalId = "apr_${UUID.randomUUID()}",
            taskId = taskId,
            callId = call.id,
            toolId = def.id,
            toolName = def.name,
            riskCategory = category,
            intentSummary = actionSummary,
            targetDataSummary = actionSummary,
            isReversible = isReversible,
            approvalEffect = approvalEffect,
            argumentsPreview = previewArgs
        )
        return ToolPolicyEvaluation.RequireUserApproval(request = request, reason = reason)
    }
}
