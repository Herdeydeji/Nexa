package com.example.domain.agent

import com.example.domain.model.AppSettings
import com.example.domain.model.ConfirmationMode

sealed interface SafetyDecision {
    data object Allow : SafetyDecision
    data class RequireUserApproval(val reason: String) : SafetyDecision
    data class Deny(val reason: String) : SafetyDecision
}

interface SafetyPolicyManager {
    fun evaluateTool(tool: AgentTool, settings: AppSettings): SafetyDecision
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
}
