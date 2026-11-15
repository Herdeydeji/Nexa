package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Immutable
class NexaHapticController(
    val enabled: Boolean,
    private val platformHaptic: HapticFeedback?
) {
    fun performPrimary() {
        if (enabled) {
            platformHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun performConfirm() {
        if (enabled) {
            platformHaptic?.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun performToggle() {
        if (enabled) {
            platformHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
}

val LocalNexaHapticFeedback = staticCompositionLocalOf {
    NexaHapticController(enabled = false, platformHaptic = null)
}

@Composable
fun rememberNexaHapticController(enabled: Boolean): NexaHapticController {
    val platformHaptic = LocalHapticFeedback.current
    return androidx.compose.runtime.remember(enabled, platformHaptic) {
        NexaHapticController(enabled = enabled, platformHaptic = platformHaptic)
    }
}
