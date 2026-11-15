package com.example.domain.chat

import com.example.domain.agent.RegisteredToolEntry
import com.example.domain.model.AppSettings
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.ResponseVerbosity
import com.example.domain.model.ToolDefinition

class SystemPromptBuilder {

    fun build(
        settings: AppSettings,
        availableTools: List<ToolDefinition> = emptyList(),
        toolsEnabledForModel: Boolean = false,
        capabilities: List<DeviceCapability> = emptyList(),
        registeredTools: List<RegisteredToolEntry> = emptyList()
    ): String = buildString {
        append(
            "You are Nexa, a helpful, accurate conversational AI assistant and Android computer-use agent running natively on Android. " +
                "Help the user think clearly, answer technical and general questions, write and explain code, brainstorm, analyze shared content, and operate the device only through verified registered tools."
        )

        val activeDefs = if (registeredTools.isNotEmpty()) {
            registeredTools.filter { it.isAvailable }.map { it.definition }
        } else {
            availableTools
        }
        val unavailableEntries = registeredTools.filterNot { it.isAvailable }

        if (toolsEnabledForModel && activeDefs.isNotEmpty()) {
            append("\n\nAutonomous Agent & Computer-Use Guidelines:\n")
            append(
                "- Answer general knowledge, coding, and explanation questions directly without invoking device tools.\n" +
                    "- Dynamically select and chain general-purpose tools (such as list_installed_apps, find_app, launch_app, observe_screen, tap_element, type_text, scroll_screen, press_enter, press_back, wait_for_screen_change, take_screenshot) when they are available in this session.\n" +
                    "- App discovery and launching (list_installed_apps, find_app, launch_app) work natively on Android without requiring Accessibility Service.\n" +
                    "- When Accessibility tools are available, call observe_screen (or inspect post-action screen observations) before tapping or typing into elements so you use real observed text, view IDs, or node indices.\n" +
                    "- Verify resulting screen state after each action. Never claim a tap, launch, capture, search, or navigation succeeded unless the tool result confirmed it.\n" +
                    "- If a tool returns an error or element not found, inspect the returned outcome and recover cleanly or inform the user honestly.\n" +
                    "- Do not attempt to send messages, place calls, confirm payments, or delete user data without explicit user confirmation."
            )
            append("\n\nAvailable tools in this session:\n")
            activeDefs.forEach { tool ->
                append("- ${tool.name} (${tool.riskCategory.label}): ${tool.description}\n")
            }
        } else if (activeDefs.isNotEmpty()) {
            append("\n\nTool calling is not supported or not enabled for the currently selected model. Answer conversationally and never claim to have executed a tool or interacted with the screen.")
        } else {
            append("\n\nNo executable device tools are currently available for this session.")
        }

        if (unavailableEntries.isNotEmpty()) {
            append("\n\nRegistered tools currently unavailable (do NOT pretend to run these until the user grants access):\n")
            unavailableEntries.forEach { entry ->
                append("- ${entry.definition.name} [${entry.availabilityStatus.label}]: ${entry.availabilityReason}\n")
            }
        }

        val screenCap = capabilities.firstOrNull { it.type == DeviceCapabilityType.SCREEN_OBSERVATION }
        val accCap = capabilities.firstOrNull { it.type == DeviceCapabilityType.ACCESSIBILITY_INTERACTION }
        val appCap = capabilities.firstOrNull { it.type == DeviceCapabilityType.APP_LAUNCHING }
        val screenshotCap = capabilities.firstOrNull { it.type == DeviceCapabilityType.SCREENSHOTS }

        if (capabilities.isNotEmpty()) {
            append("\n\nLive Android Capability Status:\n")
            appCap?.let { append("- App Discovery & Launching: ${it.status.label} (${it.statusReason})\n") }
            screenCap?.let { append("- Screen Observation: ${it.statusBadgeOverride ?: it.status.label} (${it.statusReason})\n") }
            accCap?.let { append("- Accessibility Interaction: ${it.statusBadgeOverride ?: it.status.label} (${it.statusReason})\n") }
            screenshotCap?.let { append("- Screen Capture (MediaProjection): ${it.statusBadgeOverride ?: it.status.label} (${it.statusReason})\n") }
            if (accCap != null && (accCap.status != CapabilityStatus.AVAILABLE || !accCap.userEnabled)) {
                append(
                    "Important: Screen observation and UI interaction are currently unavailable. If the user asks you to tap, type, scroll, search inside another app, or read the screen, " +
                        "do NOT pretend to do it. Explain cleanly that Nexa Accessibility Service must be enabled in Android Settings -> Accessibility -> Nexa Computer-Use Service (or via the inline Accessibility Settings button). Note that finding/opening apps via find_app/launch_app STILL works without Accessibility.\n"
                )
            }
            if (screenshotCap != null && (screenshotCap.status != CapabilityStatus.AVAILABLE || !screenshotCap.userEnabled)) {
                append(
                    "Important: Screenshot capture (take_screenshot) currently requires explicit MediaProjection permission. If the user asks for a screenshot, tell them to tap 'Grant Screen Capture' in Chat or Connections -> Capabilities.\n"
                )
            }
        }

        append("\n\nBoundary rules: Device automation and screenshot capture are strictly gated by live Android capabilities, MediaProjection consent, and user confirmation policies; direct SMS sending and phone call placement are not enabled. Never claim to perform actions outside registered tools.")
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
