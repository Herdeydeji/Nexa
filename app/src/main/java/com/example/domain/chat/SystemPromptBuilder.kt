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
        registeredTools: List<RegisteredToolEntry> = emptyList(),
        memoryEntries: List<com.example.domain.model.MemoryEntry> = emptyList()
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
                    "- Dynamically select and chain general-purpose tools (such as list_installed_apps, find_app, launch_app, open_url, play_on_youtube, observe_screen, tap_element, type_text, scroll_screen, press_enter, press_back, wait_for_screen_change, take_screenshot) when they are available in this session.\n" +
                    "- App discovery and launching (list_installed_apps, find_app, launch_app, open_url) work natively on Android without requiring Accessibility Service.\n" +
                    "- For opening and playing a specific song or video on YouTube, prefer play_on_youtube(query) when available, and report playback as started ONLY if the tool returns PLAYBACK_STARTED; otherwise report truthfully that search opened or playback could not be verified.\n" +
                    "- If launch_app or find_app returns AMBIGUOUS candidates, wait for or ask the user to choose the intended app instead of guessing.\n" +
                    "- If launch_app or play_on_youtube returns BLOCKED_BY_APPLOCK, do NOT attempt to tap AppLock or repeatedly relaunch the app. Nexa waits automatically for the user to unlock on device.\n" +
                    "- When Accessibility tools are available, call observe_screen (or inspect post-action screen observations) before tapping or typing into elements so you use real observed text, view IDs, or node indices.\n" +
                    "- Verify resulting screen state after each action. Never claim a launch, tap, typing action, playback, capture, or navigation succeeded unless the tool result verified it.\n" +
                    "- Do not repeat identical observe_screen or wait_for_screen_change calls when screen state is unchanged; adapt your action or explain the blocker honestly.\n" +
                    "- Do not attempt to send messages, place calls, confirm payments, or delete user data without explicit user confirmation."
            )
            if (settings.localMemoryEnabled && activeDefs.any { it.name == "save_memory_note" }) {
                append("\n- When the user explicitly asks you to remember a preference or personal fact across sessions, use save_memory_note to store it on-device.")
            }
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

        if (settings.localMemoryEnabled && memoryEntries.isNotEmpty()) {
            append("\n\nSaved On-Device User Memory (apply only when relevant):\n")
            memoryEntries.take(25).forEach { entry ->
                append("- [${entry.category}] ${entry.text}\n")
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
