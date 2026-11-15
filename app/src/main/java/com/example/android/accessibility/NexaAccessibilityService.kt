package com.example.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

data class AccessibilityLifecycleSnapshot(
    val isConnected: Boolean = false,
    val isInterrupted: Boolean = false,
    val isScreenLocked: Boolean = false,
    val activePackageName: String? = null,
    val activeWindowTitle: String? = null,
    val lastEventTimestampMillis: Long = 0L,
    val eventSequence: Long = 0L
)

data class AccessibilityWindowChangeSignal(
    val packageName: String?,
    val eventType: Int,
    val timestampMillis: Long,
    val sequence: Long
)

class NexaAccessibilityService : AccessibilityService() {

    private var screenStateReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        registerScreenStateReceiver()
        val isLocked = isDeviceKeyguardLocked()
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        _runtimeState.update {
            it.copy(
                isConnected = true,
                isInterrupted = false,
                isScreenLocked = isLocked,
                activePackageName = rootPkg ?: it.activePackageName,
                lastEventTimestampMillis = System.currentTimeMillis()
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return
        }

        val now = System.currentTimeMillis()
        val seq = sequenceGenerator.incrementAndGet()
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() }
        val title = if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.text.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        _runtimeState.update { current ->
            current.copy(
                isConnected = true,
                isInterrupted = false,
                activePackageName = pkg ?: current.activePackageName,
                activeWindowTitle = title ?: if (pkg != null && pkg != current.activePackageName) null else current.activeWindowTitle,
                lastEventTimestampMillis = now,
                eventSequence = seq
            )
        }

        _windowChanges.tryEmit(
            AccessibilityWindowChangeSignal(
                packageName = pkg,
                eventType = type,
                timestampMillis = now,
                sequence = seq
            )
        )
    }

    override fun onInterrupt() {
        _runtimeState.update {
            it.copy(isInterrupted = true)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearServiceState()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearServiceState()
        super.onDestroy()
    }

    fun getSafeRootNode(): AccessibilityNodeInfo? {
        return runCatching { rootInActiveWindow }.getOrNull()
    }

    fun performBackAction(): Boolean = runCatching {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }.getOrDefault(false)

    fun performHomeAction(): Boolean = runCatching {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }.getOrDefault(false)

    suspend fun dispatchTap(x: Float, y: Float, timeoutMillis: Long = 1_500L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchSingleGesture(GestureDescription.Builder().addStroke(stroke).build(), timeoutMillis)
    }

    suspend fun dispatchLongPress(x: Float, y: Float, durationMs: Long = 650L, timeoutMillis: Long = 2_000L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(500L, 1_500L))
        return dispatchSingleGesture(GestureDescription.Builder().addStroke(stroke).build(), timeoutMillis)
    }

    suspend fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L,
        timeoutMillis: Long = 2_000L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(150L, 1_000L))
        return dispatchSingleGesture(GestureDescription.Builder().addStroke(stroke).build(), timeoutMillis)
    }

    private suspend fun dispatchSingleGesture(
        gesture: GestureDescription,
        timeoutMillis: Long
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        deferred.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        deferred.complete(false)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }.getOrDefault(false)

        if (!dispatched) return false
        return withTimeoutOrNull(timeoutMillis) { deferred.await() } ?: false
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> _runtimeState.update { it.copy(isScreenLocked = true) }
                    Intent.ACTION_USER_PRESENT -> _runtimeState.update { it.copy(isScreenLocked = false) }
                    Intent.ACTION_SCREEN_ON -> _runtimeState.update {
                        it.copy(isScreenLocked = isDeviceKeyguardLocked())
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            screenStateReceiver = receiver
        }
    }

    private fun unregisterScreenStateReceiver() {
        val receiver = screenStateReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
        screenStateReceiver = null
    }

    private fun isDeviceKeyguardLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return runCatching { km?.isKeyguardLocked == true }.getOrDefault(false)
    }

    private fun clearServiceState() {
        unregisterScreenStateReceiver()
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        _runtimeState.update {
            AccessibilityLifecycleSnapshot(
                isConnected = false,
                isInterrupted = false,
                isScreenLocked = false
            )
        }
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<NexaAccessibilityService>? = null
        private val sequenceGenerator = AtomicLong(0L)

        private val _runtimeState = MutableStateFlow(AccessibilityLifecycleSnapshot())
        val runtimeState: StateFlow<AccessibilityLifecycleSnapshot> = _runtimeState.asStateFlow()

        private val _windowChanges = MutableSharedFlow<AccessibilityWindowChangeSignal>(
            replay = 0,
            extraBufferCapacity = 32
        )
        val windowChanges: SharedFlow<AccessibilityWindowChangeSignal> = _windowChanges.asSharedFlow()

        fun currentInstance(): NexaAccessibilityService? = instanceRef?.get()

        internal fun updateTestStateForTesting(snapshot: AccessibilityLifecycleSnapshot) {
            _runtimeState.value = snapshot
        }

        internal fun attachInstanceForTesting(service: NexaAccessibilityService?) {
            instanceRef = service?.let { WeakReference(it) }
        }

        internal fun emitWindowChangeForTesting(signal: AccessibilityWindowChangeSignal) {
            _windowChanges.tryEmit(signal)
        }
    }
}
