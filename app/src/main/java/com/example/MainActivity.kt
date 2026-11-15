package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.AppSettings
import com.example.ui.navigation.NexaAppShell
import com.example.ui.theme.NexaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as NexaApplication).container
        setContent {
            val settings by appContainer.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            NexaTheme(
                appearancePosture = settings.appearancePosture,
                hapticFeedbackEnabled = settings.hapticFeedbackEnabled
            ) {
                NexaAppShell(container = appContainer)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as NexaApplication).container
        container.floatingIndicatorController.setNexaForeground(true)
        container.floatingIndicatorController.notifyOverlayPermissionChanged()
    }

    override fun onPause() {
        super.onPause()
        val container = (application as NexaApplication).container
        container.floatingIndicatorController.setNexaForeground(false)
    }
}
