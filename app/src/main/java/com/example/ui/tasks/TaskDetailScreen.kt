package com.example.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.AgentTaskStatus
import com.example.ui.components.AgentActivityTimeline
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NexaSurfaceCard
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SampleDataBadge
import com.example.ui.components.SectionHeader
import com.example.ui.components.TaskStatusPill
import com.example.ui.theme.NexaTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(
    taskId: String,
    viewModel: TasksViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val task by viewModel.observeTaskDetail(taskId).collectAsStateWithLifecycle(initialValue = null)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = NexaTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Surface(
            color = colors.secondaryBackground,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("task_detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back to tasks",
                            tint = colors.textPrimary
                        )
                    }
                    Text(
                        text = "Task Details",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.borderSubtle)
                )
            }
        }

        val currentTask = task
        if (currentTask == null) {
            EmptyStateView(
                icon = Icons.Outlined.ErrorOutline,
                title = "Task not found",
                description = "This task record could not be loaded."
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (currentTask.isSampleData) {
                item(key = "sample_banner") {
                    NoticeBanner(
                        icon = Icons.Outlined.Info,
                        text = state.phase1Notice
                            ?: "Sample execution timeline for UI verification. Real tool-assisted tasks appear automatically when invoked in Chat."
                    )
                }
            }

            val isThisTaskLiveRunning = !currentTask.isSampleData &&
                state.activeRunState.activeTaskId == currentTask.id &&
                state.activeRunState.isRunning

            item(key = "overview") {
                NexaSurfaceCard(borderColor = colors.border) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TaskStatusPill(status = currentTask.status)
                        if (currentTask.isSampleData) {
                            SampleDataBadge()
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = currentTask.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "\"${currentTask.prompt}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        MetadataField("Target Runtime", currentTask.targetApp ?: "Nexa Tools", Modifier.weight(1f))
                        MetadataField("Model", currentTask.modelLabel, Modifier.weight(1f))
                    }
                    if (isThisTaskLiveRunning) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (state.activeRunState.isPaused) {
                                        viewModel.resumeLiveTask()
                                    } else {
                                        viewModel.pauseLiveTask()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("task_pause_resume_button")
                            ) {
                                Text(if (state.activeRunState.isPaused) "Resume" else "Pause")
                            }
                            OutlinedButton(
                                onClick = viewModel::cancelLiveTask,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("task_cancel_button")
                            ) {
                                Text("Cancel", color = colors.error)
                            }
                        }
                    }
                }
            }

            currentTask.verificationSummary?.let { verification ->
                item(key = "verification") {
                    val breakdown = "Verified: ${verification.verifiedCompletedCount} · Unverified: ${verification.unverifiedCompletedCount} · Failed: ${verification.failedCount} · Blocked: ${verification.blockedCount} · Remaining: ${verification.remainingCount}"
                    NoticeBanner(
                        icon = Icons.Outlined.Info,
                        text = "${verification.summaryNote}\n$breakdown",
                        accentColor = if (verification.isVerified && verification.objectiveVerified) {
                            colors.success
                        } else {
                            colors.warning
                        }
                    )
                }
            }

            currentTask.resumableSnapshot?.let { snapshot ->
                item(key = "resumable_snapshot") {
                    NoticeBanner(
                        icon = Icons.Outlined.Info,
                        text = "Resumable wait context (${snapshot.targetAppName ?: snapshot.targetPackage}): ${snapshot.reason}",
                        accentColor = colors.warning
                    )
                }
            }

            if (currentTask.requiredCapabilities.isNotEmpty()) {
                item(key = "capabilities") {
                    NexaSurfaceCard(borderColor = colors.border) {
                        SectionHeader(title = "Tools & Capabilities")
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            currentTask.requiredCapabilities.forEach { cap ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.elevatedSurface)
                                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = cap,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!currentTask.failureReason.isNullOrBlank()) {
                item(key = "failure") {
                    NoticeBanner(
                        icon = Icons.Outlined.ErrorOutline,
                        text = currentTask.failureReason,
                        accentColor = colors.error
                    )
                }
            }

            val liveUnlock = state.activeRunState.waitingForUnlock?.takeIf { it.taskId == currentTask.id }
            if (liveUnlock != null) {
                item(key = "live_unlock_wait") {
                    com.example.ui.components.UnlockWaitCard(
                        request = liveUnlock,
                        onContinue = viewModel::continueUnlockWait,
                        onCancel = viewModel::cancelLiveTask
                    )
                }
            }

            val liveAmbiguity = state.activeRunState.pendingAmbiguity?.takeIf { it.taskId == currentTask.id }
            if (liveAmbiguity != null) {
                item(key = "live_ambiguity") {
                    com.example.ui.components.AmbiguitySelectionCard(
                        request = liveAmbiguity,
                        onSelectCandidate = { selectedPkg ->
                            viewModel.selectAmbiguousApp(liveAmbiguity.selectionId, selectedPkg)
                        },
                        onCancel = {
                            viewModel.selectAmbiguousApp(liveAmbiguity.selectionId, null)
                        }
                    )
                }
            }

            val liveApproval = state.activeRunState.pendingApproval?.takeIf { it.taskId == currentTask.id }
            if (liveApproval != null) {
                item(key = "live_approval_actions") {
                    com.example.ui.components.ApprovalGateCard(
                        request = liveApproval,
                        onApprove = { viewModel.approveLiveToolCall(liveApproval.callId) },
                        onReject = { viewModel.rejectLiveToolCall(liveApproval.callId) },
                        approveTestTag = "task_approve_button",
                        rejectTestTag = "task_reject_button"
                    )
                }
            } else if (currentTask.isSampleData && currentTask.status == AgentTaskStatus.AWAITING_APPROVAL) {
                item(key = "approval_actions") {
                    NexaSurfaceCard(borderColor = colors.orange.copy(alpha = 0.45f)) {
                        SectionHeader(
                            title = "Confirmation Gate",
                            subtitle = "High-risk actions pause until explicitly approved."
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.rejectSampleTask(currentTask.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("task_reject_button")
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reject")
                            }
                            Button(
                                onClick = { viewModel.approveSampleTask(currentTask.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("task_approve_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.orange,
                                    contentColor = colors.onOrange
                                )
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Approve")
                            }
                        }
                    }
                }
            }

            item(key = "timeline") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(
                        title = "Activity Timeline",
                        subtitle = "${currentTask.steps.size} recorded steps"
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    AgentActivityTimeline(steps = currentTask.steps)
                }
            }
        }
    }
}

@Composable
private fun MetadataField(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = NexaTheme.colors
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
    }
}
