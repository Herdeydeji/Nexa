package com.example.domain.agent

import com.example.domain.model.AppSettings
import com.example.domain.model.ExecutionPlan
import com.example.domain.model.ExecutionStep
import com.example.domain.model.ExecutionStepState
import com.example.domain.model.ProviderConfig

data class OrchestratorTurnResult(
    val assistantReply: String,
    val executionPlan: ExecutionPlan? = null
)

interface AgentOrchestrator {
    suspend fun handleUserPrompt(
        prompt: String,
        activeProvider: ProviderConfig?,
        settings: AppSettings
    ): OrchestratorTurnResult
}

class Phase1AgentOrchestrator(
    private val toolRegistry: ToolRegistry,
    private val safetyPolicyManager: SafetyPolicyManager
) : AgentOrchestrator {

    override suspend fun handleUserPrompt(
        prompt: String,
        activeProvider: ProviderConfig?,
        settings: AppSettings
    ): OrchestratorTurnResult {
        val providerLabel = if (activeProvider != null && activeProvider.hasApiKey) {
            "${activeProvider.providerType.displayName} (${activeProvider.selectedModelId ?: "unselected"})"
        } else {
            "No AI provider configured"
        }

        val lower = prompt.lowercase()
        val looksLikeDeviceTask = listOf(
            "open ", "play ", "send ", "call ", "turn on", "turn off",
            "wifi", "bluetooth", "brightness", "screenshot", "launch ", "message "
        ).any { lower.contains(it) }

        if (looksLikeDeviceTask) {
            val matchedTool = when {
                lower.contains("sms") || lower.contains("message ") -> toolRegistry.findTool("comms.draft_sms")
                lower.contains("call ") -> toolRegistry.findTool("comms.prepare_call")
                lower.contains("open ") || lower.contains("play ") || lower.contains("launch ") ->
                    toolRegistry.findTool("device.launch_app")
                else -> toolRegistry.findTool("system.toggle_settings")
            }

            val safetyNote = matchedTool?.let { tool ->
                when (val decision = safetyPolicyManager.evaluateTool(tool, settings)) {
                    is SafetyDecision.RequireUserApproval -> decision.reason
                    is SafetyDecision.Deny -> decision.reason
                    SafetyDecision.Allow -> "Permitted under current confirmation policy."
                }
            } ?: "Requires Phase 2 execution runtime."

            val plan = ExecutionPlan(
                goal = prompt.trim(),
                isPreview = true,
                steps = listOf(
                    ExecutionStep(
                        id = "step_1",
                        title = "Parsed request locally",
                        detail = "Classified as a device-action prompt in Phase 1 UI mode.",
                        state = ExecutionStepState.COMPLETED,
                        toolName = "orchestrator.intent_parser"
                    ),
                    ExecutionStep(
                        id = "step_2",
                        title = "Safety policy check",
                        detail = safetyNote,
                        state = ExecutionStepState.AWAITING_CONFIRMATION,
                        toolName = matchedTool?.id
                    ),
                    ExecutionStep(
                        id = "step_3",
                        title = "Android execution runtime",
                        detail = "Device automation is disabled in Phase 1. No app or setting was modified.",
                        state = ExecutionStepState.PENDING,
                        toolName = "runtime.android_executor"
                    )
                )
            )

            val reply = buildString {
                append("I parsed your task request locally, but **autonomous Android execution is not active in Phase 1**.\n\n")
                append("- **Provider state:** `$providerLabel`\n")
                append("- **Policy evaluation:** $safetyNote\n\n")
                append("No device action was executed. Below is the non-executed step outline showing how Nexa structures tasks before Phase 2 runtime wiring.")
            }

            return OrchestratorTurnResult(
                assistantReply = reply,
                executionPlan = plan
            )
        }

        val reply = buildString {
            append("**Phase 1 Foundation Response**\n\n")
            append("Your message was saved locally to this conversation thread. Live AI completion is not connected yet in Phase 1.\n\n")
            append("- **Active provider:** `$providerLabel`\n")
            append("- **Registered tools:** ${toolRegistry.getAllTools().size} tool contracts ready\n\n")
            append("```kotlin\n// Phase 1 adapter contract\nval status = providerRegistry.getAdapter(type).generate(config, request)\n```\n\n")
            append("You can configure provider keys in **Connections -> AI Providers**, inspect device permissions in **Capabilities**, or review task workflows in **Tasks**.")
        }

        return OrchestratorTurnResult(
            assistantReply = reply,
            executionPlan = null
        )
    }
}
