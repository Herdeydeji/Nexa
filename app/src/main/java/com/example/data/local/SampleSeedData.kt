package com.example.data.local

import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.ExecutionPlan
import com.example.domain.model.ExecutionStep
import com.example.domain.model.ExecutionStepState
import com.example.domain.model.MessageRole
import com.example.domain.model.TaskSummary

object SampleSeedData {

    const val PREVIEW_CONVERSATION_ID = "conv_phase1_preview"

    fun buildPreviewConversation(now: Long, converters: NexaTypeConverters): Pair<ConversationEntity, List<MessageEntity>> {
        val conv = ConversationEntity(
            id = PREVIEW_CONVERSATION_ID,
            title = "YouTube Asake Playback Preview",
            isSamplePreview = true,
            createdAtMillis = now - 180_000L,
            updatedAtMillis = now - 60_000L,
            lastMessagePreview = "Sample UI preview of Nexa's multi-step agent execution panel."
        )

        val youtubePlan = ExecutionPlan(
            goal = "Open YouTube and play the latest Asake music video",
            isPreview = true,
            steps = listOf(
                ExecutionStep(
                    id = "p1",
                    title = "Understanding request",
                    detail = "Target app: YouTube · Query: \"Asake latest music video\"",
                    state = ExecutionStepState.COMPLETED,
                    toolName = "orchestrator.intent_parser",
                    timestampLabel = "00:01"
                ),
                ExecutionStep(
                    id = "p2",
                    title = "Opening YouTube",
                    detail = "Resolve com.google.android.youtube launcher intent",
                    state = ExecutionStepState.COMPLETED,
                    toolName = "device.launch_app",
                    timestampLabel = "00:02"
                ),
                ExecutionStep(
                    id = "p3",
                    title = "Searching videos",
                    detail = "Locate search field and submit query via Accessibility tree",
                    state = ExecutionStepState.RUNNING,
                    toolName = "ui.perform_gesture",
                    timestampLabel = "00:04"
                ),
                ExecutionStep(
                    id = "p4",
                    title = "Selecting a result",
                    detail = "Waiting for search results feed to settle",
                    state = ExecutionStepState.PENDING,
                    toolName = "ui.observe_screen"
                ),
                ExecutionStep(
                    id = "p5",
                    title = "Starting playback",
                    detail = "Tap top official music video item",
                    state = ExecutionStepState.PENDING,
                    toolName = "ui.perform_gesture"
                ),
                ExecutionStep(
                    id = "p6",
                    title = "Verifying playback",
                    detail = "Confirm media player controls are active",
                    state = ExecutionStepState.PENDING,
                    toolName = "ui.observe_screen"
                )
            )
        )

        val youtubeSummary = TaskSummary(
            taskId = "sample_task_youtube_asake",
            title = "Play latest Asake music video on YouTube",
            status = AgentTaskStatus.RUNNING,
            completedSteps = 2,
            totalSteps = 6,
            targetApp = "YouTube",
            isSampleData = true
        )

        val msg1 = MessageEntity(
            id = "msg_preview_1",
            conversationId = PREVIEW_CONVERSATION_ID,
            role = MessageRole.USER,
            content = "Open YouTube and play the latest Asake music video.",
            createdAtMillis = now - 170_000L,
            executionPlanJson = null,
            taskSummaryJson = null,
            attachmentsJson = null,
            isPreviewMessage = true
        )

        val msg2 = MessageEntity(
            id = "msg_preview_2",
            conversationId = PREVIEW_CONVERSATION_ID,
            role = MessageRole.ASSISTANT,
            content = buildString {
                append("Sure. I'll open YouTube, search for Asake music videos and attempt to play the most relevant result.\n\n")
                append("This thread demonstrates Nexa's **Phase 1 execution interface** (static preview only — no real device automation is running):\n")
                append("- Structured step timeline with live state indicators\n")
                append("- Tool registry contracts mapped per step\n")
                append("- Code & diagnostic formatting support\n\n")
                append("```kotlin\n// Planned Phase 2 dispatch\nexecutionRuntime.execute(RuntimeAction(\"device.launch_app\", mapOf(\"package\" to \"com.google.android.youtube\")))\n```")
            },
            createdAtMillis = now - 160_000L,
            executionPlanJson = converters.encodeExecutionPlan(youtubePlan),
            taskSummaryJson = converters.encodeTaskSummary(youtubeSummary),
            attachmentsJson = null,
            isPreviewMessage = true
        )

        return conv to listOf(msg1, msg2)
    }

    fun buildSampleTasks(now: Long, converters: NexaTypeConverters): List<Pair<AgentTaskEntity, List<TaskStepEntity>>> {
        val runningTask = AgentTaskEntity(
            id = "sample_task_youtube_asake",
            title = "Play latest Asake music video on YouTube",
            prompt = "Open YouTube and play the latest Asake music video.",
            status = AgentTaskStatus.RUNNING,
            isSampleData = true,
            providerLabel = "Sample Preview",
            modelLabel = "UI Showcase",
            targetApp = "YouTube",
            requiredCapabilitiesJson = converters.encodeStringList(
                listOf("App launching", "Screen observation", "Accessibility interaction")
            ),
            failureReason = null,
            createdAtMillis = now - 170_000L,
            updatedAtMillis = now - 60_000L
        ) to listOf(
            TaskStepEntity("st_1_1", "sample_task_youtube_asake", 0, "Understanding request", "Parsed media playback intent for artist 'Asake' on YouTube.", ExecutionStepState.COMPLETED, "orchestrator.intent_parser", "15:01:02"),
            TaskStepEntity("st_1_2", "sample_task_youtube_asake", 1, "Opening YouTube", "Prepared launch intent for com.google.android.youtube.", ExecutionStepState.COMPLETED, "device.launch_app", "15:01:03"),
            TaskStepEntity("st_1_3", "sample_task_youtube_asake", 2, "Searching videos", "Inspecting search input node in preview timeline.", ExecutionStepState.RUNNING, "ui.perform_gesture", "15:01:05"),
            TaskStepEntity("st_1_4", "sample_task_youtube_asake", 3, "Selecting a result", "Queued until search results list renders.", ExecutionStepState.PENDING, "ui.observe_screen", null),
            TaskStepEntity("st_1_5", "sample_task_youtube_asake", 4, "Starting playback", "Queued tap on top video card.", ExecutionStepState.PENDING, "ui.perform_gesture", null),
            TaskStepEntity("st_1_6", "sample_task_youtube_asake", 5, "Verifying playback", "Queued playback state verification.", ExecutionStepState.PENDING, "ui.observe_screen", null)
        )

        val approvalTask = AgentTaskEntity(
            id = "sample_task_sms_eta",
            title = "Draft ETA message to Tunde via SMS",
            prompt = "Text Tunde that I am 15 minutes away.",
            status = AgentTaskStatus.AWAITING_APPROVAL,
            isSampleData = true,
            providerLabel = "Sample Preview",
            modelLabel = "UI Showcase",
            targetApp = "Messages",
            requiredCapabilitiesJson = converters.encodeStringList(
                listOf("Contacts", "SMS")
            ),
            failureReason = null,
            createdAtMillis = now - 500_000L,
            updatedAtMillis = now - 420_000L
        ) to listOf(
            TaskStepEntity("st_2_1", "sample_task_sms_eta", 0, "Understanding request", "Identified recipient 'Tunde' and outbound message draft.", ExecutionStepState.COMPLETED, "orchestrator.intent_parser", "14:55:10"),
            TaskStepEntity("st_2_2", "sample_task_sms_eta", 1, "Safety policy check", "High-risk communication action paused for explicit human confirmation.", ExecutionStepState.AWAITING_CONFIRMATION, "comms.draft_sms", "14:55:11"),
            TaskStepEntity("st_2_3", "sample_task_sms_eta", 2, "Open SMS composer", "Waiting for user approval before opening Messages draft.", ExecutionStepState.PENDING, "comms.draft_sms", null)
        )

        val completedTask = AgentTaskEntity(
            id = "sample_task_browser_docs",
            title = "Open Android Developers Compose docs",
            prompt = "Look up Jetpack Compose edge-to-edge documentation in browser.",
            status = AgentTaskStatus.COMPLETED,
            isSampleData = true,
            providerLabel = "Sample Preview",
            modelLabel = "UI Showcase",
            targetApp = "Browser",
            requiredCapabilitiesJson = converters.encodeStringList(
                listOf("Browser interaction")
            ),
            failureReason = null,
            createdAtMillis = now - 3_600_000L,
            updatedAtMillis = now - 3_540_000L
        ) to listOf(
            TaskStepEntity("st_3_1", "sample_task_browser_docs", 0, "Understanding request", "Resolved query to developer.android.com Compose layouts guide.", ExecutionStepState.COMPLETED, "orchestrator.intent_parser", "14:04:01"),
            TaskStepEntity("st_3_2", "sample_task_browser_docs", 1, "Validating URL safety", "Confirmed HTTPS domain on allowlist.", ExecutionStepState.COMPLETED, "safety.url_check", "14:04:02"),
            TaskStepEntity("st_3_3", "sample_task_browser_docs", 2, "Launching browser intent", "Sample completion state recorded for UI demonstration.", ExecutionStepState.COMPLETED, "browser.open_url", "14:04:03")
        )

        val failedTask = AgentTaskEntity(
            id = "sample_task_wifi_toggle",
            title = "Toggle Wi-Fi diagnostic profile",
            prompt = "Turn off Wi-Fi and switch to metered check.",
            status = AgentTaskStatus.FAILED,
            isSampleData = true,
            providerLabel = "Sample Preview",
            modelLabel = "UI Showcase",
            targetApp = "System Settings",
            requiredCapabilitiesJson = converters.encodeStringList(
                listOf("Wi-Fi", "Accessibility interaction")
            ),
            failureReason = "Blocked in sample preview: Android 10+ restricts silent Wi-Fi toggling and Phase 1 Accessibility runtime is offline.",
            createdAtMillis = now - 7_200_000L,
            updatedAtMillis = now - 7_150_000L
        ) to listOf(
            TaskStepEntity("st_4_1", "sample_task_wifi_toggle", 0, "Understanding request", "Parsed system radio change request.", ExecutionStepState.COMPLETED, "orchestrator.intent_parser", "13:02:10"),
            TaskStepEntity("st_4_2", "sample_task_wifi_toggle", 1, "Checking Wi-Fi capability", "System panel permission required; direct API call blocked.", ExecutionStepState.FAILED, "system.toggle_settings", "13:02:11"),
            TaskStepEntity("st_4_3", "sample_task_wifi_toggle", 2, "Cleanup & abort", "Remaining steps cancelled cleanly after capability error.", ExecutionStepState.CANCELLED, "orchestrator.abort", "13:02:12")
        )

        return listOf(runningTask, approvalTask, completedTask, failedTask)
    }
}
