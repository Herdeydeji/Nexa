package com.example.domain.chat

import com.example.domain.model.AppSettings
import com.example.domain.model.ResponseVerbosity
import com.example.domain.model.ToolDefinition

class SystemPromptBuilder {

    fun build(
        settings: AppSettings,
        availableTools: List<ToolDefinition> = emptyList(),
        toolsEnabledForModel: Boolean = false
    ): String = buildString {
        append(
            "You are Nexa, a helpful, accurate conversational AI assistant running natively on Android. " +
                "Help the user think clearly, answer technical and general questions, write and explain code, brainstorm, and analyze shared text or supported images."
        )

        if (toolsEnabledForModel && availableTools.isNotEmpty()) {
            append("\n\nAutonomous Agent & Tool Usage:\n")
            append(
                "- Answer general knowledge, coding, brainstorming, and explanation questions directly without invoking tools.\n" +
                    "- Invoke registered tools dynamically whenever accurate arithmetic, live device date/time/timezone, or verified Android device state is required.\n" +
                    "- You may run sequential or multiple tool calls across turns when earlier outputs inform later steps.\n" +
                    "- Ground your final answer strictly in observed tool results. Never guess, extrapolate, or fabricate tool outputs.\n" +
                    "- If a tool returns an error or is rejected by the user or safety policy, explain the failure honestly."
            )
            append("\n\nRegistered tools available in this session:\n")
            availableTools.forEach { tool ->
                append("- ${tool.name} (${tool.riskCategory.label}): ${tool.description}\n")
            }
        } else if (availableTools.isNotEmpty()) {
            append("\n\nTool calling is not supported or not enabled for the currently selected model. Answer conversationally and never claim to have executed a tool.")
        }

        append("\n\nCapability boundaries: Device automation (tapping third-party apps, UI gestures, sending SMS, placing phone calls, or Android Accessibility actions) is not enabled in this build. Never claim you performed an action outside the registered tools.")
        append("\n\nStyle preference: ")
        when (settings.responseVerbosity) {
            ResponseVerbosity.CONCISE -> append("Keep answers concise, direct, and high-signal unless the user asks for depth.")
            ResponseVerbosity.BALANCED -> append("Provide clear, well-structured explanations with practical examples when helpful.")
            ResponseVerbosity.DETAILED -> append("Provide thorough, step-by-step explanations and edge-case details.")
        }

        val custom = settings.customSystemPrompt.trim()
        if (custom.isNotEmpty()) {
            append("\n\nUser custom instructions:\n")
            append(custom)
        }
    }
}
