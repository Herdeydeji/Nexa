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
        if (def.name == "launch_app" && !settings.allowAppLaunchSuggestions) {
            return ToolPolicyEvaluation.Deny(
                "App launching is disabled in Settings > Task Permissions ('Allow app launch suggestions')."
            )
        }

        val sensitiveTargetMatch = detectSensitiveUiTarget(def.name, args)
        val effectiveCategory = if (sensitiveTargetMatch != null) {
            ToolRiskCategory.EXTERNAL_SIDE_EFFECT
        } else {
            def.riskCategory
        }

        val mustConfirm = when (effectiveCategory) {
            // Non-bypassable gates: sensitive, destructive, and external side-effect actions
            // always require explicit human approval regardless of confirmation mode.
            ToolRiskCategory.SENSITIVE,
            ToolRiskCategory.DESTRUCTIVE,
            ToolRiskCategory.EXTERNAL_SIDE_EFFECT -> true
            // User-chosen policy: auto-run reversible UI steps in SENSITIVE_ONLY and SUPERVISED_AUTO;
            // only gate them when user explicitly selects ALWAYS_CONFIRM.
            ToolRiskCategory.REVERSIBLE -> settings.confirmationMode == ConfirmationMode.ALWAYS_CONFIRM
            ToolRiskCategory.READ_ONLY -> settings.confirmationMode == ConfirmationMode.ALWAYS_CONFIRM
        }

        if (!mustConfirm) {
            return ToolPolicyEvaluation.Allow
        }

        val actionSummary = tool.describeAction(args)
        val isReversible = effectiveCategory == ToolRiskCategory.READ_ONLY ||
            effectiveCategory == ToolRiskCategory.REVERSIBLE
        val approvalEffect = when {
            sensitiveTargetMatch != null ->
                "Targets a potentially sensitive or irreversible UI action ('$sensitiveTargetMatch'). Requires explicit confirmation."
            effectiveCategory == ToolRiskCategory.READ_ONLY ->
                "Allows Nexa to execute '${def.name}' and return the read-only result to the model."
            effectiveCategory == ToolRiskCategory.REVERSIBLE ->
                "Allows Nexa to run this reversible action and report the observed result."
            effectiveCategory == ToolRiskCategory.SENSITIVE ->
                "Grants explicit one-time approval for sensitive tool '${def.name}'."
            effectiveCategory == ToolRiskCategory.DESTRUCTIVE ->
                "Grants explicit one-time approval for this potentially irreversible action."
            else ->
                "Permits an external side-effect action via '${def.name}'."
        }
        val reason = if (sensitiveTargetMatch != null) {
            "'${def.name}' targets '$sensitiveTargetMatch', which may trigger a message, purchase, or deletion."
        } else {
            "${def.name} is classified as ${effectiveCategory.label} and requires explicit confirmation."
        }

        val previewArgs = if (settings.redactSensitiveStepsInLogs && effectiveCategory == ToolRiskCategory.SENSITIVE) {
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
            riskCategory = effectiveCategory,
            intentSummary = actionSummary,
            targetDataSummary = actionSummary,
            isReversible = isReversible,
            approvalEffect = approvalEffect,
            argumentsPreview = previewArgs
        )
        return ToolPolicyEvaluation.RequireUserApproval(request = request, reason = reason)
    }

    private fun detectSensitiveUiTarget(toolName: String, args: JSONObject): String? {
        if (toolName !in setOf("tap_element", "long_press_element", "type_text", "press_enter")) {
            return null
        }
        val fields = listOf(
            args.optString("text"),
            args.optString("content_description"),
            args.optString("view_id"),
            args.optString("hint_or_label")
        ).joinToString(" ").trim()
        if (fields.isEmpty()) return null
        val match = SENSITIVE_UI_TARGET_REGEX.find(fields)
        return match?.value
    }

    companion object {
        private val SENSITIVE_UI_TARGET_REGEX = Regex(
            pattern = """\b(pay|buy|purchase|checkout|place\s+order|confirm\s+payment|transfer|send\s+money|send\s+message|send\s+sms|delete|erase|remove\s+account|uninstall|call\s+now)\b""",
            option = RegexOption.IGNORE_CASE
        )
    }
}
