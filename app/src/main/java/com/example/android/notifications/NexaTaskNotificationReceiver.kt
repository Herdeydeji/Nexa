package com.example.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.NexaApplication

class NexaTaskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val app = context.applicationContext as? NexaApplication ?: return
        when (intent.action) {
            NexaTaskNotificationManager.ACTION_CANCEL_AGENT_TASK -> {
                app.container.agentRunController.cancelActiveRun()
                app.container.taskNotificationManager.dismiss()
            }
            NexaTaskNotificationManager.ACTION_PAUSE_AGENT_TASK -> {
                app.container.agentRunController.pause()
            }
            NexaTaskNotificationManager.ACTION_RESUME_AGENT_TASK -> {
                app.container.agentRunController.resume()
            }
            NexaTaskNotificationManager.ACTION_CONTINUE_AGENT_TASK -> {
                app.container.agentRunController.submitUnlockContinue()
            }
        }
    }
}
