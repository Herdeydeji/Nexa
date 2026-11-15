package com.example.domain.agent

import com.example.domain.model.AgentTask
import com.example.domain.model.AppSettings
import com.example.domain.model.ChatMessage
import com.example.domain.model.Conversation
import com.example.domain.model.DeviceCapability
import com.example.domain.model.MemoryEntry
import com.example.domain.model.ProviderConfig
import com.example.domain.security.ApiKeyMasker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticsExportBundle(
    val content: String,
    val maskedCredentialsApplied: Boolean,
    val redactedSensitiveStepsApplied: Boolean,
    val conversationCount: Int,
    val taskCount: Int
)

object DiagnosticsExporter {
    fun buildReport(
        settings: AppSettings,
        providers: List<ProviderConfig>,
        capabilities: List<DeviceCapability>,
        tasks: List<AgentTask>,
        conversations: List<Conversation>,
        messagesByConversation: Map<String, List<ChatMessage>>,
        memories: List<MemoryEntry>,
        knownSecretsToScrub: List<String> = emptyList(),
        generatedAtMillis: Long = System.currentTimeMillis()
    ): DiagnosticsExportBundle {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val raw = buildString {
            appendLine("=== Nexa Android Diagnostics & Transcripts ===")
            appendLine("Generated: ${dateFormat.format(Date(generatedAtMillis))}")
            appendLine("Mask credentials in exports: ${settings.maskCredentialsInExports}")
            appendLine("Redact sensitive steps in logs: ${settings.redactSensitiveStepsInLogs}")
            appendLine()

            appendLine("--- Settings Summary ---")
            appendLine("Appearance: ${settings.appearancePosture.name}")
            appendLine("Compact message spacing: ${settings.compactMessageSpacing}")
            appendLine("Haptic feedback: ${settings.hapticFeedbackEnabled}")
            appendLine("Confirmation mode: ${settings.confirmationMode.name}")
            appendLine("Require approval for external comms: ${settings.requireApprovalForExternalComms}")
            appendLine("Background task queue: ${settings.allowBackgroundTaskQueue}")
            appendLine("App launch suggestions: ${settings.allowAppLaunchSuggestions}")
            appendLine("Limits: steps=${settings.maxStepsPerTask}, toolCalls=${settings.maxToolCallsPerTask}, toolTimeout=${settings.toolTimeoutSeconds}s, taskTimeout=${settings.taskTimeoutSeconds}s, maxToolChars=${settings.maxToolResultChars}")
            appendLine("Floating indicator: ${settings.floatingIndicatorMode.name}, appLockWait=${settings.appLockWaitTimeoutSeconds}s, loopGuard=${settings.noProgressRetryLimit}")
            appendLine("Save conversation history: ${settings.saveConversationHistory}, retention=${settings.historyRetentionDays}d")
            appendLine("Local memory enabled: ${settings.localMemoryEnabled} (${memories.size} saved note(s)), rememberModelPerThread=${settings.rememberPreferredModelPerThread}")
            appendLine()

            appendLine("--- AI Providers ---")
            providers.forEach { provider ->
                val keyLabel = when {
                    !provider.hasApiKey -> "No key"
                    settings.maskCredentialsInExports -> provider.maskedApiKey.ifBlank { "[MASKED]" }
                    else -> provider.maskedApiKey.ifBlank { "[STORED]" }
                }
                appendLine("- ${provider.providerType.displayName} (default=${provider.isDefault}): status=${provider.connectionStatus}, model=${provider.selectedModelId ?: "none"}, key=$keyLabel")
            }
            appendLine()

            appendLine("--- Capabilities (${capabilities.size}) ---")
            capabilities.forEach { cap ->
                appendLine("- ${cap.type.title} [${cap.type.category.label}]: ${cap.statusBadgeOverride ?: cap.status.label} | userEnabled=${cap.userEnabled} | ${cap.statusReason}")
            }
            appendLine()

            appendLine("--- Recent Tasks (${tasks.size}) ---")
            tasks.forEach { task ->
                appendLine("Task [${task.id}] '${task.title}' status=${task.status.name} (${task.providerLabel}/${task.modelLabel})")
                task.verificationSummary?.let { v ->
                    appendLine("  Verification: verified=${v.isVerified}, objectiveVerified=${v.objectiveVerified}, summary=${v.summaryNote}")
                }
                task.steps.forEachIndexed { idx, step ->
                    val detailText = if (settings.redactSensitiveStepsInLogs &&
                        (step.toolName == "type_text" || step.detail.contains("redacted", ignoreCase = true))
                    ) {
                        step.detail
                    } else {
                        step.detail
                    }
                    appendLine("  Step ${idx + 1} [${step.state.name}/${step.toolName ?: "none"}]: ${step.title} -> $detailText")
                }
            }
            appendLine()

            appendLine("--- Chat Transcripts (${conversations.size}) ---")
            conversations.forEach { conv ->
                appendLine("Conversation [${conv.id}] '${conv.title}' (sample=${conv.isSamplePreview}, preferredProvider=${conv.preferredProviderId ?: "default"}, preferredModel=${conv.preferredModelId ?: "default"})")
                val msgs = messagesByConversation[conv.id].orEmpty()
                msgs.forEach { msg ->
                    appendLine("  [${msg.role.name}] ${msg.content}")
                }
            }
        }

        val finalContent = if (settings.maskCredentialsInExports) {
            ApiKeyMasker.sanitizeTranscriptOrDiagnostics(raw, knownSecretsToScrub)
        } else {
            raw
        }

        return DiagnosticsExportBundle(
            content = finalContent,
            maskedCredentialsApplied = settings.maskCredentialsInExports,
            redactedSensitiveStepsApplied = settings.redactSensitiveStepsInLogs,
            conversationCount = conversations.size,
            taskCount = tasks.size
        )
    }
}
