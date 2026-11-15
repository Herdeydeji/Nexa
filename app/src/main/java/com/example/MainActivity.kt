package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.navigation.NexaAppShell
import com.example.ui.theme.NexaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as NexaApplication).container
        setContent {
            NexaTheme {
                NexaAppShell(container = appContainer)
            }
        }
    }
}
