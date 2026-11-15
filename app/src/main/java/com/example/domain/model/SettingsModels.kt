package com.example.domain.model

enum class AppearancePosture(val label: String) {
    OBSIDIAN_DARK("Obsidian Dark (Default)"),
    HIGH_CONTRAST_DARK("High-Contrast Dark")
}

enum class ConfirmationMode(val label: String, val summary: String) {
    ALWAYS_CONFIRM("Always ask before actions", "Pause and request approval before every device step."),
    SENSITIVE_ONLY("Confirm sensitive actions", "Ask before calls, SMS, purchases, or external apps."),
    SUPERVISED_AUTO("Supervised dry-run", "Execute low-risk navigation steps automatically in Phase 2.")
}

enum class ResponseVerbosity(val label: String) {
    CONCISE("Concise"),
    BALANCED("Balanced"),
    DETAILED("Detailed with step logs")
}

data class AppSettings(
    // General preferences
    val compactMessageSpacing: Boolean = false,
    val showSampleBadges: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    // Appearance
    val appearancePosture: AppearancePosture = AppearancePosture.OBSIDIAN_DARK,
    val showCodeLineNumbers: Boolean = true,
    // Agent behaviour
    val responseVerbosity: ResponseVerbosity = ResponseVerbosity.BALANCED,
    val autoExpandExecutionPanel: Boolean = true,
    val maxStepsPerTask: Int = 8,
    val maxToolCallsPerTask: Int = 12,
    val toolTimeoutSeconds: Int = 10,
    val taskTimeoutSeconds: Int = 120,
    val maxToolResultChars: Int = 8_000,
    val customSystemPrompt: String = "",
    // Task permissions
    val allowBackgroundTaskQueue: Boolean = false,
    val allowAppLaunchSuggestions: Boolean = true,
    // Confirmation preferences
    val confirmationMode: ConfirmationMode = ConfirmationMode.SENSITIVE_ONLY,
    val requireApprovalForExternalComms: Boolean = true,
    // Conversation history
    val saveConversationHistory: Boolean = true,
    val historyRetentionDays: Int = 30,
    // Memory settings
    val localMemoryEnabled: Boolean = false,
    val rememberPreferredModelPerThread: Boolean = true,
    // Security & privacy
    val maskCredentialsInExports: Boolean = true,
    val redactSensitiveStepsInLogs: Boolean = true,
    val biometricLockOnApproval: Boolean = false
) {
    fun toExecutionLimits(contextTokens: Int?): AgentExecutionLimits = AgentExecutionLimits(
        maxIterations = maxStepsPerTask.coerceIn(2, 24),
        maxToolCalls = maxToolCallsPerTask.coerceIn(1, 32),
        toolTimeoutMillis = (toolTimeoutSeconds.coerceIn(2, 60)) * 1000L,
        taskTimeoutMillis = (taskTimeoutSeconds.coerceIn(15, 300)) * 1000L,
        maxToolResultChars = maxToolResultChars.coerceIn(500, 32_000),
        maxContextTokens = contextTokens
    )
}
