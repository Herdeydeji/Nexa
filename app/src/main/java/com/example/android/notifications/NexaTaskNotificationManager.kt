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

    fun showRunningProgress(taskId: String, title: String, detail: String) {
        if (!permissionManager.isNotificationPostingPermitted()) return
        ensureChannel()

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, NexaTaskNotificationReceiver::class.java).apply {
            action = ACTION_CANCEL_AGENT_TASK
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nexa: ${title.take(48)}")
            .setContentText(detail.take(120))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun showTerminalOutcome(title: String, detail: String, isError: Boolean = false) {
        if (!permissionManager.isNotificationPostingPermitted()) return
        ensureChannel()

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (isError) "Nexa stopped: ${title.take(40)}" else "Nexa done: ${title.take(42)}")
            .setContentText(detail.take(120))
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
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
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
