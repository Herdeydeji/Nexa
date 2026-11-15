package com.example.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.android.runtime.AndroidPermissionManager

class NexaTaskNotificationManager(
    private val context: Context,
    private val permissionManager: AndroidPermissionManager
) {
    private val manager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
        ensureChannel()
    }

    fun showRunningProgress(
        taskId: String,
        title: String,
        detail: String,
        isPaused: Boolean = false,
        waitingForManualContinue: Boolean = false
    ) {
        if (!permissionManager.isNotificationPostingPermitted()) return
        ensureChannel()

        val contentPendingIntent = openNexaPendingIntent()
        val cancelPendingIntent = broadcastPendingIntent(ACTION_CANCEL_AGENT_TASK, taskId, 1)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nexa: ${title.take(48)}")
            .setContentText(detail.take(140))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)

        if (waitingForManualContinue) {
            builder.addAction(
                0,
                "Continue",
                broadcastPendingIntent(ACTION_CONTINUE_AGENT_TASK, taskId, 4)
            )
        } else if (isPaused) {
            builder.addAction(
                0,
                "Resume",
                broadcastPendingIntent(ACTION_RESUME_AGENT_TASK, taskId, 3)
            )
        } else {
            builder.addAction(
                0,
                "Pause",
                broadcastPendingIntent(ACTION_PAUSE_AGENT_TASK, taskId, 2)
            )
        }
        builder.addAction(0, "Stop", cancelPendingIntent)

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun showWaitingForUnlock(
        taskId: String,
        title: String,
        message: String,
        showContinueAction: Boolean
    ) {
        if (!permissionManager.isNotificationPostingPermitted()) return
        ensureChannel()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.take(60))
            .setContentText(message.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openNexaPendingIntent())

        if (showContinueAction) {
            builder.addAction(
                0,
                "Continue",
                broadcastPendingIntent(ACTION_CONTINUE_AGENT_TASK, taskId, 4)
            )
        }
        builder.addAction(
            0,
            "Stop",
            broadcastPendingIntent(ACTION_CANCEL_AGENT_TASK, taskId, 1)
        )

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun showTerminalOutcome(title: String, detail: String, isError: Boolean = false) {
        if (!permissionManager.isNotificationPostingPermitted()) return
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (isError) "Nexa stopped: ${title.take(40)}" else "Nexa done: ${title.take(42)}")
            .setContentText(detail.take(140))
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openNexaPendingIntent())
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun dismiss() {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun openNexaPendingIntent(): PendingIntent {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcastPendingIntent(action: String, taskId: String, requestSalt: Int): PendingIntent {
        val intent = Intent(context, NexaTaskNotificationReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode() + requestSalt,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_agent_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_agent_desc)
                setShowBadge(false)
            }
            runCatching { manager?.createNotificationChannel(channel) }
        }
    }

    companion object {
        const val CHANNEL_ID = "nexa_agent_execution"
        const val NOTIFICATION_ID = 4041
        const val ACTION_CANCEL_AGENT_TASK = "com.example.action.CANCEL_AGENT_TASK"
        const val ACTION_PAUSE_AGENT_TASK = "com.example.action.PAUSE_AGENT_TASK"
        const val ACTION_RESUME_AGENT_TASK = "com.example.action.RESUME_AGENT_TASK"
        const val ACTION_CONTINUE_AGENT_TASK = "com.example.action.CONTINUE_AGENT_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
