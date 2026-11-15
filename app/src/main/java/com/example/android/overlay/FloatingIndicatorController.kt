package com.example.android.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.android.accessibility.NexaAccessibilityService
import com.example.android.notifications.NexaTaskNotificationManager
import com.example.android.runtime.AndroidPermissionManager
import com.example.domain.agent.ActiveAgentRunState
import com.example.domain.agent.AgentRunController
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.FloatingIndicatorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

interface FloatingOverlayHost {
    fun showOrUpdate(state: FloatingIndicatorUiState)
    fun dismiss()
}

class AndroidFloatingOverlayHost(
    private val context: Context
) : FloatingOverlayHost {
    private var serviceStarted = false

    override fun showOrUpdate(state: FloatingIndicatorUiState) {
        if (!state.isVisible) {
            dismiss()
            return
        }
        val intent = Intent(context, FloatingAgentOverlayService::class.java).apply {
            action = FloatingAgentOverlayService.ACTION_SYNC
        }
        runCatching {
            if (!serviceStarted) {
                ContextCompat.startForegroundService(context, intent)
                serviceStarted = true
            } else {
                context.startService(intent)
            }
        }
    }

    override fun dismiss() {
        if (!serviceStarted) return
        serviceStarted = false
        runCatching {
            context.stopService(Intent(context, FloatingAgentOverlayService::class.java))
        }
    }
}

class FloatingIndicatorController(
    private val context: Context,
    private val agentRunController: AgentRunController,
    private val settingsFlow: Flow<AppSettings>,
    private val taskNotificationManager: NexaTaskNotificationManager? = null,
    private val overlayPermissionProvider: () -> Boolean = {
        AndroidPermissionManager(context).canDrawOverlays()
    },
    private val overlayHost: FloatingOverlayHost = AndroidFloatingOverlayHost(context),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val isNexaInForegroundFlow = MutableStateFlow(true)
    private val currentObjectiveFlow = MutableStateFlow("")
    private val permissionRefreshTick = MutableStateFlow(0)

    private val _uiState = MutableStateFlow(FloatingIndicatorUiState())
    val uiState: StateFlow<FloatingIndicatorUiState> = _uiState.asStateFlow()

    private var collectionJob: Job? = null

    init {
        collectionJob = scope.launch {
            combine(
                agentRunController.state,
                settingsFlow,
                isNexaInForegroundFlow,
                NexaAccessibilityService.runtimeState,
                permissionRefreshTick
            ) { runState, settings, nexaInFg, accessibilityRuntime, _ ->
                buildUiState(
                    runState = runState,
                    settings = settings,
                    nexaInFg = nexaInFg,
                    accessibilityForegroundPkg = accessibilityRuntime.activePackageName
                )
            }.collect { nextState ->
                _uiState.value = nextState
                dispatchHostUpdate(nextState)
            }
        }
    }

    fun setNexaForeground(inForeground: Boolean) {
        isNexaInForegroundFlow.value = inForeground
    }

    fun setCurrentObjective(objective: String) {
        currentObjectiveFlow.value = objective.trim()
    }

    fun notifyOverlayPermissionChanged() {
        permissionRefreshTick.value += 1
    }

    private fun buildUiState(
        runState: ActiveAgentRunState,
        settings: AppSettings,
        nexaInFg: Boolean,
        accessibilityForegroundPkg: String?
    ): FloatingIndicatorUiState {
        val activeTaskId = runState.activeTaskId
        val isRunning = runState.isRunning
        if (!isRunning || activeTaskId == null) {
            return FloatingIndicatorUiState(
                isVisible = false,
                visualState = when (runState.runStatus) {
                    AgentRunStatus.COMPLETED -> FloatingIndicatorVisualState.COMPLETED
                    AgentRunStatus.FAILED -> FloatingIndicatorVisualState.FAILED
                    else -> FloatingIndicatorVisualState.IDLE
                },
                taskId = activeTaskId
            )
        }

        val liveForegroundPkg = accessibilityForegroundPkg?.takeIf { it.isNotBlank() }
            ?: runState.currentForegroundPackage?.takeIf { it.isNotBlank() }

        val movedToExternalApp = !nexaInFg ||
            (liveForegroundPkg != null && liveForegroundPkg != context.packageName) ||
            runState.waitingForUnlock != null

        val shouldActivateForMode = when (settings.floatingIndicatorMode) {
            FloatingIndicatorMode.DISABLED -> false
            FloatingIndicatorMode.ALWAYS_WHILE_RUNNING -> true
            FloatingIndicatorMode.AUTO_EXTERNAL_APPS -> movedToExternalApp
        }

        if (!shouldActivateForMode) {
            return FloatingIndicatorUiState(isVisible = false, taskId = activeTaskId)
        }

        val visualState = when {
            runState.isPaused || runState.runStatus == AgentRunStatus.PAUSED ->
                FloatingIndicatorVisualState.PAUSED
            runState.waitingForUnlock != null || runState.runStatus == AgentRunStatus.WAITING_FOR_USER_UNLOCK ->
                FloatingIndicatorVisualState.WAITING_FOR_USER
            runState.pendingAmbiguity != null || runState.runStatus == AgentRunStatus.WAITING_FOR_AMBIGUITY_CHOICE ->
                FloatingIndicatorVisualState.WAITING_FOR_USER
            runState.pendingApproval != null || runState.runStatus == AgentRunStatus.AWAITING_APPROVAL ->
                FloatingIndicatorVisualState.WAITING_FOR_USER
            runState.runStatus == AgentRunStatus.EXECUTING_TOOL ||
                runState.runStatus == AgentRunStatus.SELECTING_TOOL ->
                FloatingIndicatorVisualState.EXECUTING
            else -> FloatingIndicatorVisualState.THINKING
        }

        val canOverlay = overlayPermissionProvider()
        val objective = currentObjectiveFlow.value.ifBlank { runState.currentTargetAppName ?: "Nexa Task" }
        val unlockWait = runState.waitingForUnlock
        val ambiguity = runState.pendingAmbiguity
        val approval = runState.pendingApproval

        val blockerTitle = when {
            unlockWait != null -> unlockWait.title
            ambiguity != null -> "Choose app for '${ambiguity.query}'"
            approval != null -> "Approve ${approval.toolName}"
            else -> null
        }
        val blockerMsg = when {
            unlockWait != null -> unlockWait.message
            ambiguity != null -> ambiguity.reason
            approval != null -> approval.intentSummary
            else -> null
        }

        return FloatingIndicatorUiState(
            isVisible = canOverlay,
            visualState = visualState,
            taskId = activeTaskId,
            objective = objective,
            currentAction = runState.statusDetail.ifBlank { visualState.label },
            targetAppName = runState.currentTargetAppName ?: unlockWait?.targetAppName,
            foregroundApp = liveForegroundPkg,
            completedSteps = runState.completedStepCount,
            totalSteps = runState.totalStepCount,
            blockerTitle = blockerTitle,
            blockerMessage = blockerMsg,
            showManualContinue = unlockWait?.manualContinueRequired == true,
            canPause = !runState.isPaused && unlockWait == null && ambiguity == null && approval == null,
            canResume = runState.isPaused,
            canCancel = true,
            ambiguitySelectionId = ambiguity?.selectionId,
            ambiguityCandidates = ambiguity?.candidates.orEmpty(),
            usingNotificationFallback = !canOverlay
        )
    }

    private fun dispatchHostUpdate(state: FloatingIndicatorUiState) {
        val taskId = state.taskId
        if (state.isVisible) {
            overlayHost.showOrUpdate(state)
        } else {
            overlayHost.dismiss()
            if (state.usingNotificationFallback && taskId != null) {
                val title = state.blockerTitle ?: state.objective.ifBlank { "Running task" }
                val detail = state.blockerMessage ?: state.currentAction
                if (state.blockerTitle != null) {
                    taskNotificationManager?.showWaitingForUnlock(
                        taskId = taskId,
                        title = title,
                        message = detail,
                        showContinueAction = state.showManualContinue
                    )
                } else {
                    taskNotificationManager?.showRunningProgress(
                        taskId = taskId,
                        title = title,
                        detail = detail,
                        isPaused = state.visualState == FloatingIndicatorVisualState.PAUSED,
                        waitingForManualContinue = state.showManualContinue
                    )
                }
            }
        }
    }

    fun shutdown() {
        collectionJob?.cancel()
        overlayHost.dismiss()
    }
}
