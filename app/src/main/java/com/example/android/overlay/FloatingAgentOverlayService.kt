package com.example.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NexaApplication
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingAgentOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    private var isExpanded = false
    private var lastX = 24
    private var lastY = 180

    private lateinit var pillView: LinearLayout
    private lateinit var dotView: View
    private lateinit var pillTitle: TextView
    private lateinit var pillStepBadge: TextView

    private lateinit var cardView: LinearLayout
    private lateinit var cardStatusText: TextView
    private lateinit var cardObjectiveText: TextView
    private lateinit var cardActionText: TextView
    private lateinit var cardBlockerText: TextView
    private lateinit var ambiguityContainer: LinearLayout
    private lateinit var continueButton: Button
    private lateinit var pauseResumeButton: Button
    private lateinit var stopButton: Button
    private lateinit var returnButton: Button
    private lateinit var collapseButton: Button

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ensureForegroundNotification()
        observeIndicatorState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            removeOverlayView()
            stopSelf()
            return START_NOT_STICKY
        }
        val app = applicationContext as? NexaApplication
        val currentState = app?.container?.floatingIndicatorController?.uiState?.value
        if (currentState != null) {
            applyUiState(currentState)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        removeOverlayView()
        super.onDestroy()
    }

    private fun observeIndicatorState() {
        val app = applicationContext as? NexaApplication ?: return
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            app.container.floatingIndicatorController.uiState.collectLatest { state ->
                applyUiState(state)
            }
        }
    }

    private fun applyUiState(state: FloatingIndicatorUiState) {
        if (!state.isVisible) {
            removeOverlayView()
            stopSelf()
            return
        }
        val app = applicationContext as? NexaApplication
        val canDraw = app?.container?.permissionManager?.canDrawOverlays() ?: true
        if (!canDraw) {
            removeOverlayView()
            stopSelf()
            return
        }

        ensureOverlayAttached()

        // Confirmed auto-expand policy: expand automatically on waits, recovery, manual continue, or disambiguation.
        if (state.autoExpandOnState || state.showManualContinue || state.ambiguityCandidates.isNotEmpty()) {
            isExpanded = true
        }

        val accentColor = when (state.visualState) {
            FloatingIndicatorVisualState.EXECUTING,
            FloatingIndicatorVisualState.THINKING -> Color.parseColor("#F36B21")
            FloatingIndicatorVisualState.VERIFYING -> Color.parseColor("#38BDF8")
            FloatingIndicatorVisualState.RECOVERING -> Color.parseColor("#F97316")
            FloatingIndicatorVisualState.WAITING_FOR_USER,
            FloatingIndicatorVisualState.BLOCKED -> Color.parseColor("#F59E0B")
            FloatingIndicatorVisualState.PAUSED -> Color.parseColor("#60A5FA")
            FloatingIndicatorVisualState.COMPLETED -> Color.parseColor("#22C55E")
            FloatingIndicatorVisualState.FAILED -> Color.parseColor("#EF4444")
            FloatingIndicatorVisualState.IDLE -> Color.parseColor("#9CA3AF")
        }

        (dotView.background as? GradientDrawable)?.setColor(accentColor)
        val compactPhase = state.executionPhase.label.takeIf { state.executionPhase != com.example.domain.model.AgentExecutionPhase.IDLE }
            ?: state.visualState.label
        pillTitle.text = "Nexa · $compactPhase"
        val stepText = if (state.totalSteps > 0) "${state.completedSteps}/${state.totalSteps}" else "Active"
        pillStepBadge.text = stepText

        val verSuffix = state.verificationLevel?.let { " · L${it.levelNumber}" }.orEmpty()
        cardStatusText.text = "${compactPhase.uppercase()}$verSuffix"
        cardStatusText.setTextColor(accentColor)
        cardObjectiveText.text = state.currentSubgoal.ifBlank { state.objective.ifBlank { "Nexa Task" } }

        val fgSuffix = state.foregroundApp?.let { " · $it" } ?: ""
        val verifiedLine = state.lastVerifiedAction?.let { "\nVerified: $it" }.orEmpty()
        val nextLine = state.nextPlannedAction?.let { "\nNext: $it" }.orEmpty()
        cardActionText.text = "${state.currentAction}$fgSuffix$verifiedLine$nextLine"

        if (!state.blockerTitle.isNullOrBlank() || !state.blockerMessage.isNullOrBlank()) {
            cardBlockerText.visibility = View.VISIBLE
            cardBlockerText.text = listOfNotNull(state.blockerTitle, state.blockerMessage).joinToString(": ")
        } else {
            cardBlockerText.visibility = View.GONE
        }

        continueButton.visibility = if (state.showManualContinue) View.VISIBLE else View.GONE
        pauseResumeButton.visibility = if (state.canPause || state.canResume) View.VISIBLE else View.GONE
        pauseResumeButton.text = if (state.canResume) "Resume" else "Pause"
        stopButton.visibility = if (state.canCancel) View.VISIBLE else View.GONE

        ambiguityContainer.removeAllViews()
        val selId = state.ambiguitySelectionId
        if (selId != null && state.ambiguityCandidates.isNotEmpty()) {
            ambiguityContainer.visibility = View.VISIBLE
            state.ambiguityCandidates.take(4).forEach { candidate ->
                val detailSuffix = candidate.subtitle ?: candidate.packageName.substringAfterLast('.')
                val btn = createActionButton(
                    text = "${candidate.displayName} ($detailSuffix)",
                    bgColor = Color.parseColor("#20232A"),
                    textColor = Color.WHITE
                ) {
                    app?.container?.agentRunController?.submitAmbiguityChoice(selId, candidate.packageName)
                }
                ambiguityContainer.addView(btn)
            }
        } else {
            ambiguityContainer.visibility = View.GONE
        }

        pillView.visibility = if (isExpanded) View.GONE else View.VISIBLE
        cardView.visibility = if (isExpanded) View.VISIBLE else View.GONE

        val lp = layoutParams
        val rv = rootView
        if (lp != null && rv != null) {
            runCatching { windowManager?.updateViewLayout(rv, lp) }
        }
    }

    private fun ensureOverlayAttached() {
        if (rootView != null) return
        val wm = windowManager ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Compact floating pill
        pillView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#181A1F"))
                setStroke(dp(1), Color.parseColor("#F36B21"))
            }
            elevation = dp(6).toFloat()
        }

        dotView = View(this).apply {
            val size = dp(10)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F36B21"))
            }
        }
        pillTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        pillStepBadge = TextView(this).apply {
            setTextColor(Color.parseColor("#F36B21"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }
        pillView.addView(dotView)
        pillView.addView(pillTitle)
        pillView.addView(pillStepBadge)

        // Expanded compact control card
        cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(276), LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#181A1F"))
                setStroke(dp(1), Color.parseColor("#2B2F38"))
            }
            elevation = dp(8).toFloat()
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cardStatusText = TextView(this).apply {
            setTextColor(Color.parseColor("#F36B21"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        collapseButton = createActionButton("Hide", Color.parseColor("#20232A"), Color.parseColor("#A8B0BE")) {
            isExpanded = false
            pillView.visibility = View.VISIBLE
            cardView.visibility = View.GONE
        }
        headerRow.addView(cardStatusText)
        headerRow.addView(collapseButton)

        cardObjectiveText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(2))
        }
        cardActionText = TextView(this).apply {
            setTextColor(Color.parseColor("#A8B0BE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        cardBlockerText = TextView(this).apply {
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(4))
            visibility = View.GONE
        }
        ambiguityContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(4))
        }

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val app = applicationContext as? NexaApplication
        continueButton = createActionButton("Continue", Color.parseColor("#F36B21"), Color.parseColor("#101114")) {
            app?.container?.agentRunController?.submitUnlockContinue()
        }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        }
        pauseResumeButton = createActionButton("Pause", Color.parseColor("#20232A"), Color.WHITE) {
            val ctrl = app?.container?.agentRunController ?: return@createActionButton
            if (ctrl.state.value.isPaused) ctrl.resume() else ctrl.pause()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        }
        stopButton = createActionButton("Stop", Color.parseColor("#3B1C1F"), Color.parseColor("#FCA5A5")) {
            app?.container?.agentRunController?.cancelActiveRun()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        }
        returnButton = createActionButton("Nexa", Color.parseColor("#20232A"), Color.parseColor("#F36B21")) {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            runCatching { startActivity(launchIntent) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        }

        controlsRow.addView(continueButton)
        controlsRow.addView(pauseResumeButton)
        controlsRow.addView(stopButton)
        controlsRow.addView(returnButton)

        cardView.addView(headerRow)
        cardView.addView(cardObjectiveText)
        cardView.addView(cardActionText)
        cardView.addView(cardBlockerText)
        cardView.addView(ambiguityContainer)
        cardView.addView(controlsRow)

        container.addView(pillView)
        container.addView(cardView)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }

        installDragAndTapHandler(pillView, container, params, wm)

        val added = runCatching {
            wm.addView(container, params)
            true
        }.getOrDefault(false)

        if (added) {
            rootView = container
            layoutParams = params
        }
    }

    private fun installDragAndTapHandler(
        handleView: View,
        container: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        handleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        moved = true
                    }
                    val dm = resources.displayMetrics
                    val clamped = FloatingOverlayBounds.clampPosition(
                        x = initialX + dx,
                        y = initialY + dy,
                        viewWidth = container.width.coerceAtLeast(dp(120)),
                        viewHeight = container.height.coerceAtLeast(dp(48)),
                        screenWidth = dm.widthPixels,
                        screenHeight = dm.heightPixels
                    )
                    params.x = clamped.first
                    params.y = clamped.second
                    lastX = clamped.first
                    lastY = clamped.second
                    runCatching { wm.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        isExpanded = !isExpanded
                        pillView.visibility = if (isExpanded) View.GONE else View.VISIBLE
                        cardView.visibility = if (isExpanded) View.VISIBLE else View.GONE
                        runCatching { wm.updateViewLayout(container, params) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeOverlayView() {
        val rv = rootView ?: return
        runCatching { windowManager?.removeView(rv) }
        rootView = null
        layoutParams = null
    }

    private fun createActionButton(
        text: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        minHeight = dp(36)
        minimumHeight = dp(36)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(bgColor)
        }
        setOnClickListener { onClick() }
    }

    private fun ensureForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_overlay_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_overlay_desc)
                setShowBadge(false)
            }
            runCatching { nm?.createNotificationChannel(channel) }
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nexa Floating Indicator")
            .setContentText("Active while running device task")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val CHANNEL_ID = "nexa_floating_overlay"
        const val NOTIFICATION_ID = 4042
        const val ACTION_SYNC = "com.example.action.OVERLAY_SYNC"
        const val ACTION_DISMISS = "com.example.action.OVERLAY_DISMISS"
    }
}
