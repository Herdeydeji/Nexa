package com.example.domain.model

enum class AppearancePosture(
    val label: String,
    val description: String
) {
    SYSTEM("Follow System", "Match Android device dark or light appearance automatically."),
    OBSIDIAN_DARK("Obsidian Dark", "Deep obsidian canvas with vibrant Nexa orange accents."),
    LIGHT("Daylight Light", "Crisp daylight surfaces tuned for bright environments."),
    HIGH_CONTRAST_DARK("High-Contrast Dark", "True black background with high-contrast borders and ink.")
}

enum class ConfirmationMode(val label: String, val summary: String) {
    ALWAYS_CONFIRM("Always ask before actions", "Pause and request approval before every device step."),
    SENSITIVE_ONLY("Confirm sensitive actions", "Ask before calls, SMS, purchases, or external apps."),
    SUPERVISED_AUTO("Supervised auto-run", "Execute low-risk navigation steps automatically while still gating sensitive actions.")
}

enum class ResponseVerbosity(val label: String) {
    CONCISE("Concise"),
    BALANCED("Balanced"),
    DETAILED("Detailed with step logs")
}

enum class FloatingIndicatorMode(val label: String, val summary: String) {
    AUTO_EXTERNAL_APPS(
        "Auto when in other apps",
        "Automatically float status pill when a running task leaves Nexa."
    ),
    ALWAYS_WHILE_RUNNING(
        "Always while running",
        "Keep floating indicator visible for the entire active task."
    ),
    DISABLED(
        "Off (notification only)",
        "Use standard task notifications instead of a screen overlay."
    )
}

data class MemoryEntry(
    val id: String,
    val text: String,
    val category: String = "Preference",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

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
    // Phase 4.6 Floating Agent Indicator & Recovery
    val floatingIndicatorMode: FloatingIndicatorMode = FloatingIndicatorMode.AUTO_EXTERNAL_APPS,
    val appLockWaitTimeoutSeconds: Int = 180,
    val noProgressRetryLimit: Int = 3,
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
        maxContextTokens = contextTokens,
        noProgressRetryLimit = noProgressRetryLimit.coerceIn(2, 6)
    )
}
