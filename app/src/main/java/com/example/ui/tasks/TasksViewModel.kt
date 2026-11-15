package com.example.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.TaskRepository
import com.example.domain.agent.ActiveAgentRunState
import com.example.domain.agent.AgentRunController
import com.example.domain.model.AgentTask
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.ApprovalDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TasksUiState(
    val selectedTab: AgentTaskStatus = AgentTaskStatus.RUNNING,
    val includeSampleData: Boolean = true,
    val allTasks: List<AgentTask> = emptyList(),
    val filteredTasks: List<AgentTask> = emptyList(),
    val countsByStatus: Map<AgentTaskStatus, Int> = emptyMap(),
    val activeRunState: ActiveAgentRunState = ActiveAgentRunState(),
    val phase1Notice: String? = null
)

class TasksViewModel(
    private val taskRepository: TaskRepository,
    private val agentRunController: AgentRunController = AgentRunController()
) : ViewModel() {

    private val selectedTab = MutableStateFlow(AgentTaskStatus.RUNNING)
    private val includeSampleData = MutableStateFlow(true)
    private val phase1Notice = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TasksUiState> = combine(
        taskRepository.allTasks,
        selectedTab,
        includeSampleData,
        agentRunController.state,
        phase1Notice
    ) { tasks, tab, showSamples, activeRun, notice ->
        val visiblePool = if (showSamples) tasks else tasks.filterNot { it.isSampleData }
        val counts = AgentTaskStatus.entries.associateWith { status ->
            visiblePool.count { it.status == status }
        }
        TasksUiState(
            selectedTab = tab,
            includeSampleData = showSamples,
            allTasks = visiblePool,
            filteredTasks = visiblePool.filter { it.status == tab },
            countsByStatus = counts,
            activeRunState = activeRun,
            phase1Notice = notice
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TasksUiState()
    )

    fun selectTab(status: AgentTaskStatus) {
        selectedTab.value = status
    }

    fun setIncludeSampleData(include: Boolean) {
        includeSampleData.value = include
    }

    fun observeTaskDetail(taskId: String): Flow<AgentTask?> =
        taskRepository.observeTaskWithSteps(taskId)

    fun pauseLiveTask() {
        agentRunController.pause()
    }

    fun resumeLiveTask() {
        agentRunController.resume()
    }

    fun cancelLiveTask() {
        agentRunController.cancelActiveRun()
    }

    fun approveLiveToolCall(callId: String) {
        agentRunController.submitApproval(callId, ApprovalDecision.APPROVE)
    }

    fun rejectLiveToolCall(callId: String) {
        agentRunController.submitApproval(callId, ApprovalDecision.REJECT)
    }

    fun continueUnlockWait() {
        agentRunController.submitUnlockContinue()
    }

    fun selectAmbiguousApp(selectionId: String, packageName: String?) {
        agentRunController.submitAmbiguityChoice(selectionId, packageName)
    }

    fun approveSampleTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.updateSampleTaskStatus(taskId, AgentTaskStatus.COMPLETED)
            phase1Notice.value =
                "Sample preview marked Approved/Completed locally. No device action was executed."
        }
    }

    fun rejectSampleTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.updateSampleTaskStatus(taskId, AgentTaskStatus.FAILED)
            phase1Notice.value =
                "Sample preview marked Rejected/Failed locally."
        }
    }

    fun clearNotice() {
        phase1Notice.value = null
    }

    class Factory(
        private val taskRepository: TaskRepository,
        private val agentRunController: AgentRunController = AgentRunController()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TasksViewModel(taskRepository, agentRunController) as T
        }
    }
}
