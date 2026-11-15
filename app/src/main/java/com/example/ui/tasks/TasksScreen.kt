package com.example.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.AgentTask
import com.example.domain.model.AgentTaskStatus
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NexaSurfaceCard
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SampleDataBadge
import com.example.ui.components.TaskStatusPill
import com.example.ui.theme.NexaBackground
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaBorderSubtle
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaSecondaryBackground
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary

@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onOpenTaskDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaBackground)
    ) {
        Surface(
            color = NexaSecondaryBackground,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.tasks_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = NexaTextPrimary
                        )
                        Text(
                            text = if (state.includeSampleData) {
                                "Viewing sample preview workflows"
                            } else {
                                "Live execution queue (0 active)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = NexaTextSecondary
                        )
                    }
                    FilterChip(
                        selected = state.includeSampleData,
                        onClick = { viewModel.setIncludeSampleData(!state.includeSampleData) },
                        label = { Text("Sample Data") },
                        modifier = Modifier.testTag("tasks_sample_toggle"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NexaOrangeMuted,
                            selectedLabelColor = NexaOrange,
                            containerColor = NexaElevatedSurface,
                            labelColor = NexaTextSecondary
                        )
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AgentTaskStatus.entries.forEach { tab ->
                        val selected = state.selectedTab == tab
                        val count = state.countsByStatus[tab] ?: 0
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) NexaOrangeMuted else NexaElevatedSurface)
                                .border(
                                    1.dp,
                                    if (selected) NexaOrange else NexaBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { viewModel.selectTab(tab) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("tasks_tab_${tab.name.lowercase()}")
                        ) {
                            Text(
                                text = "${tab.label} ($count)",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) NexaOrange else NexaTextSecondary
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(NexaBorderSubtle)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("tasks_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.includeSampleData) {
                item(key = "sample_notice") {
                    NoticeBanner(
                        icon = Icons.Outlined.Info,
                        text = stringResource(R.string.tasks_sample_banner)
                    )
                }
            }

            if (state.filteredTasks.isEmpty()) {
                item(key = "empty_tasks") {
                    val emptyIcon = when (state.selectedTab) {
                        AgentTaskStatus.RUNNING -> Icons.Outlined.PlayCircleOutline
                        AgentTaskStatus.AWAITING_APPROVAL -> Icons.Outlined.HourglassTop
                        AgentTaskStatus.COMPLETED -> Icons.Outlined.AssignmentTurnedIn
                        AgentTaskStatus.FAILED -> Icons.Outlined.ErrorOutline
                    }
                    EmptyStateView(
                        icon = emptyIcon,
                        title = "No ${state.selectedTab.label.lowercase()} tasks",
                        description = if (state.includeSampleData) {
                            "No tasks currently match this filter."
                        } else {
                            "Real autonomous tasks will appear here once Phase 2 execution runtime is connected. Toggle 'Sample Data' above to preview UI states."
                        }
                    )
                }
            } else {
                items(state.filteredTasks, key = { it.id }) { task ->
                    TaskListCard(
                        task = task,
                        onClick = { onOpenTaskDetail(task.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskListCard(
    task: AgentTask,
    onClick: () -> Unit
) {
    NexaSurfaceCard(
        onClick = onClick,
        borderColor = NexaBorder,
        modifier = Modifier.testTag("task_card_${task.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskStatusPill(status = task.status)
            if (task.isSampleData) {
                SampleDataBadge()
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = task.title,
            style = MaterialTheme.typography.titleMedium,
            color = NexaTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = task.prompt,
            style = MaterialTheme.typography.bodySmall,
            color = NexaTextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = listOfNotNull(task.targetApp, task.modelLabel).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = NexaTextSecondary
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Open task details",
                tint = NexaTextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
