package com.example.domain.chat

import com.example.domain.model.AppSettings
import com.example.domain.model.ResponseVerbosity

class SystemPromptBuilder {

    fun build(settings: AppSettings): String = buildString {
        append(
            "You are Nexa, a helpful, accurate conversational AI assistant running natively on Android. " +
                "Help the user think clearly, answer technical and general questions, write and explain code, brainstorm, and analyze shared text or supported images."
        )
        append("\n\nCapability boundaries: Device automation (tapping apps, sending SMS, changing system settings, or executing Android Accessibility actions) is not enabled in this build. Never claim you performed an action on the user's phone.")
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
