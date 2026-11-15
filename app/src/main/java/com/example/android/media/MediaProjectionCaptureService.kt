package com.example.android.media

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NexaApplication
import com.example.R

class MediaProjectionCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val sessionManager = (applicationContext as? NexaApplication)?.container?.mediaProjectionSessionManager

        when (action) {
            ACTION_STOP_CAPTURE -> {
                sessionManager?.stopSession(revokedBySystem = false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_CAPTURE -> {
                ensureChannel()
                val notification = buildOngoingNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null || sessionManager == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                val attached = sessionManager.attachStartedProjection(resultCode, resultData)
                if (!attached) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val sessionManager = (applicationContext as? NexaApplication)?.container?.mediaProjectionSessionManager
        if (sessionManager?.state?.value?.isSessionActive == true) {
            sessionManager.stopSession(revokedBySystem = true)
        }
        super.onDestroy()
    }

    private fun buildOngoingNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.media_projection_notif_title))
        .setContentText(getString(R.string.media_projection_notif_body))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                101,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0,
            getString(R.string.stop_screen_capture),
            PendingIntent.getService(
                this,
                102,
                Intent(this, MediaProjectionCaptureService::class.java).apply {
                    action = ACTION_STOP_CAPTURE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_capture_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_capture_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "nexa_media_projection"
        const val NOTIFICATION_ID = 4042
        const val ACTION_START_CAPTURE = "com.example.action.START_MEDIA_PROJECTION"
        const val ACTION_STOP_CAPTURE = "com.example.action.STOP_MEDIA_PROJECTION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun buildStartIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
    }
}
