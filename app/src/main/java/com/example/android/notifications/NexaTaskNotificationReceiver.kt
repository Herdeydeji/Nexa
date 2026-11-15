package com.example.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.NexaApplication

class NexaTaskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != NexaTaskNotificationManager.ACTION_CANCEL_AGENT_TASK) return
        val app = context.applicationContext as? NexaApplication ?: return
        app.container.agentRunController.cancelActiveRun()
        app.container.taskNotificationManager.dismiss()
    }
}
