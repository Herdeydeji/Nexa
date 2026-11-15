package com.example.android.overlay

import com.example.domain.model.AmbiguityCandidateItem

enum class FloatingIndicatorVisualState(val label: String) {
    IDLE("Idle"),
    THINKING("Thinking"),
    EXECUTING("Executing"),
    WAITING_FOR_USER("Waiting for User"),
    BLOCKED("Blocked"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

data class FloatingIndicatorUiState(
    val isVisible: Boolean = false,
    val visualState: FloatingIndicatorVisualState = FloatingIndicatorVisualState.IDLE,
    val taskId: String? = null,
    val objective: String = "",
    val currentAction: String = "",
    val targetAppName: String? = null,
    val foregroundApp: String? = null,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val blockerTitle: String? = null,
    val blockerMessage: String? = null,
    val showManualContinue: Boolean = false,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canCancel: Boolean = false,
    val ambiguitySelectionId: String? = null,
    val ambiguityCandidates: List<AmbiguityCandidateItem> = emptyList(),
    val usingNotificationFallback: Boolean = false
)

object FloatingOverlayBounds {
    fun clampPosition(
        x: Int,
        y: Int,
        viewWidth: Int,
        viewHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        marginPx: Int = 12
    ): Pair<Int, Int> {
        val minX = marginPx.coerceAtLeast(0)
        val maxX = (screenWidth - viewWidth - marginPx).coerceAtLeast(minX)
        val minY = marginPx.coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight - marginPx).coerceAtLeast(minY)
        return x.coerceIn(minX, maxX) to y.coerceIn(minY, maxY)
    }
}
