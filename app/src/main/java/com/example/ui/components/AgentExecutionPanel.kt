package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.ExecutionPlan
import com.example.domain.model.ExecutionStep
import com.example.domain.model.ExecutionStepState
import com.example.domain.model.StepKind
import com.example.domain.model.TaskSummary
import com.example.domain.model.ToolApprovalRequest
import com.example.domain.model.ToolRiskCategory
import com.example.ui.theme.NexaBackground
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaError
import com.example.ui.theme.NexaErrorContainer
import com.example.ui.theme.NexaInfo
import com.example.ui.theme.NexaInfoContainer
import com.example.ui.theme.NexaNeutral
import com.example.ui.theme.NexaOnOrange
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaSecondaryBackground
import com.example.ui.theme.NexaSuccess
import com.example.ui.theme.NexaSuccessContainer
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary
import com.example.ui.theme.NexaTextTertiary
import com.example.ui.theme.NexaWarning
import com.example.ui.theme.NexaWarningContainer

@Composable
fun ExpandableAgentExecutionPanel(
    plan: ExecutionPlan,
    initiallyExpanded: Boolean = true,
    showSampleBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(plan.goal) { mutableStateOf(initiallyExpanded) }
    val completedCount = plan.steps.count { it.state == ExecutionStepState.COMPLETED }
    val subtitleText = if (plan.isPreview) {
        "$completedCount of ${plan.steps.size} steps · Not executed on device"
    } else {
        val verificationNote = plan.verificationSummary?.summaryNote
        if (!verificationNote.isNullOrBlank()) {
            "$completedCount of ${plan.steps.size} steps · $verificationNote"
        } else {
            "$completedCount of ${plan.steps.size} steps · Executed on device"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NexaSecondaryBackground)
            .border(1.dp, NexaBorder, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("execution_panel_toggle"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NexaOrangeMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = NexaOrange,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Agent Execution Panel",
                        style = MaterialTheme.typography.titleSmall,
                        color = NexaTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (plan.isPreview && showSampleBadge) {
                        StatusPill(
                            label = "UI PREVIEW",
                            textColor = NexaOrange,
                            containerColor = NexaOrangeMuted,
                            showDot = false
                        )
                    } else if (!plan.isPreview && plan.verificationSummary != null) {
                        val verified = plan.verificationSummary.isVerified
                        StatusPill(
                            label = if (verified) "VERIFIED" else "UNVERIFIED",
                            textColor = if (verified) NexaSuccess else NexaWarning,
                            containerColor = if (verified) NexaSuccessContainer else NexaWarningContainer,
                            showDot = false
                        )
                    }
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaTextSecondary
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse execution panel" else "Expand execution panel",
                tint = NexaTextSecondary
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexaBackground.copy(alpha = 0.45f))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                AgentActivityTimeline(steps = plan.steps)
            }
        }
    }
}

@Composable
fun AgentActivityTimeline(
    steps: List<ExecutionStep>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            val isLast = index == steps.lastIndex
            val (icon, color) = stepStateVisuals(step.state)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = step.state.label,
                            tint = color,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .height(34.dp)
                                .background(NexaBorder)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = if (isLast) 0.dp else 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (step.state == ExecutionStepState.PENDING) NexaTextSecondary else NexaTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = step.state.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = color
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaTextSecondary
                    )
                    if (!step.toolName.isNullOrBlank() || !step.timestampLabel.isNullOrBlank() || step.kind != StepKind.EXECUTED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stepKindLabel(step.kind),
                                style = MaterialTheme.typography.labelSmall,
                                color = color.copy(alpha = 0.9f)
                            )
                            if (!step.toolName.isNullOrBlank()) {
                                Text(
                                    text = step.toolName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NexaTextTertiary
                                )
                            }
                            if (!step.timestampLabel.isNullOrBlank()) {
                                Text(
                                    text = step.timestampLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NexaTextTertiary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ApprovalGateCard(
    request: ToolApprovalRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    approveTestTag: String = "approve_tool_button",
    rejectTestTag: String = "reject_tool_button"
) {
    val (riskFg, riskBg) = when (request.riskCategory) {
        ToolRiskCategory.READ_ONLY -> NexaSuccess to NexaSuccessContainer
        ToolRiskCategory.REVERSIBLE -> NexaInfo to NexaInfoContainer
        ToolRiskCategory.SENSITIVE,
        ToolRiskCategory.EXTERNAL_SIDE_EFFECT -> NexaWarning to NexaWarningContainer
        ToolRiskCategory.DESTRUCTIVE -> NexaError to NexaErrorContainer
    }
    NexaSurfaceCard(
        modifier = modifier.testTag("approval_gate_card"),
        containerColor = NexaElevatedSurface,
        borderColor = NexaWarning.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = NexaWarning,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Action Approval Required",
                    style = MaterialTheme.typography.titleSmall,
                    color = NexaTextPrimary
                )
            }
            StatusPill(
                label = request.riskCategory.label,
                textColor = riskFg,
                containerColor = riskBg,
                showDot = false
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = request.intentSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = NexaTextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Tool: ${request.toolName} (${request.toolId}) · ${if (request.isReversible) "Reversible" else "Non-reversible"}",
            style = MaterialTheme.typography.labelSmall,
            color = NexaTextSecondary
        )
        if (request.argumentsPreview.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Parameters: ${request.argumentsPreview}",
                style = MaterialTheme.typography.bodySmall,
                color = NexaTextSecondary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = request.approvalEffect,
            style = MaterialTheme.typography.bodySmall,
            color = NexaTextTertiary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag(rejectTestTag)
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reject")
            }
            Button(
                onClick = onApprove,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag(approveTestTag),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NexaOrange,
                    contentColor = NexaOnOrange
                )
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Approve")
            }
        }
    }
}

@Composable
fun TaskSummaryCard(
    summary: TaskSummary,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NexaSurfaceCard(
        modifier = modifier.testTag("task_summary_card"),
        containerColor = NexaElevatedSurface,
        borderColor = NexaBorder,
        onClick = { onOpenTask(summary.taskId) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Structured Task Summary",
                style = MaterialTheme.typography.labelSmall,
                color = NexaTextSecondary
            )
            TaskStatusPill(status = summary.status)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = summary.title,
            style = MaterialTheme.typography.titleSmall,
            color = NexaTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    append("${summary.completedSteps}/${summary.totalSteps} steps")
                    if (!summary.targetApp.isNullOrBlank()) {
                        append(" · ${summary.targetApp}")
                    }
                    if (summary.isSampleData) {
                        append(" · Sample")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = NexaTextSecondary
            )
            Text(
                text = "View details ->",
                style = MaterialTheme.typography.labelMedium,
                color = NexaOrange
            )
        }
    }
}

private fun stepStateVisuals(state: ExecutionStepState): Pair<ImageVector, Color> {
    return when (state) {
        ExecutionStepState.COMPLETED -> Icons.Outlined.CheckCircle to NexaSuccess
        ExecutionStepState.RUNNING -> Icons.Outlined.PlayCircleOutline to NexaOrange
        ExecutionStepState.PENDING -> Icons.Outlined.HourglassEmpty to NexaNeutral
        ExecutionStepState.FAILED -> Icons.Outlined.ErrorOutline to NexaError
        ExecutionStepState.AWAITING_CONFIRMATION -> Icons.Outlined.PauseCircleOutline to NexaWarning
        ExecutionStepState.CANCELLED -> Icons.Outlined.Cancel to NexaNeutral
    }
}

private fun stepKindLabel(kind: StepKind): String = when (kind) {
    StepKind.INTENT -> "Intent"
    StepKind.EXECUTED -> "Executed"
    StepKind.RESULT -> "Result"
    StepKind.APPROVAL -> "Approval"
    StepKind.VERIFICATION -> "Verified"
    StepKind.ERROR -> "Error"
    StepKind.CANCELLED -> "Cancelled"
}
