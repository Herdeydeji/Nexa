package com.example.android.accessibility

import android.content.Context
import android.os.Build
import android.view.WindowManager
import com.example.android.media.ScreenshotCaptureBackend
import com.example.android.ocr.ScreenOcrProcessor
import com.example.android.ocr.SnapshotFusionHelper
import com.example.android.runtime.AndroidPermissionManager
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ScreenObservation
import com.example.domain.model.UiNodeSource
import com.example.domain.model.WindowDialogType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface ScreenObservationBridge {
    fun currentObservation(): ScreenObservation
    suspend fun awaitWindowChange(
        afterSequence: Long,
        timeoutMillis: Long
    ): AccessibilityWindowChangeSignal?
    fun latestEventSequence(): Long
}

class LiveAccessibilityScreenBridge(
    private val permissionManager: AndroidPermissionManager,
    private val nodeInspector: AccessibilityNodeInspector = AccessibilityNodeInspector(),
    private val context: Context? = null
) : ScreenObservationBridge {

    override fun currentObservation(): ScreenObservation {
        val now = System.currentTimeMillis()
        val perm = permissionManager.inspect()
        val snapshot = NexaAccessibilityService.runtimeState.value
        val (screenW, screenH) = resolveDisplayDimensions()
        if (perm.accessibilityState != AccessibilityRuntimeState.SERVICE_ENABLED) {
            return ScreenObservation(
                timestampMillis = now,
                activePackageName = snapshot.activePackageName,
                windowTitle = snapshot.activeWindowTitle,
                isInteractive = false,
                isDeviceLocked = perm.isDeviceLocked,
                isRestrictedWindow = perm.isDeviceLocked,
                screenWidthPx = screenW,
                screenHeightPx = screenH,
                hasSparseHierarchy = true,
                hierarchyCompleteness = 0f,
                observationError = perm.accessibilityReason
            )
        }
        val service = NexaAccessibilityService.currentInstance()
            ?: return ScreenObservation(
                timestampMillis = now,
                activePackageName = snapshot.activePackageName,
                windowTitle = snapshot.activeWindowTitle,
                isInteractive = false,
                isRestrictedWindow = true,
                screenWidthPx = screenW,
                screenHeightPx = screenH,
                hasSparseHierarchy = true,
                hierarchyCompleteness = 0f,
                observationError = "Temporarily unavailable — Nexa Accessibility Service instance is not bound."
            )

        val root = service.getSafeRootNode()
        val imeOpen = service.isSoftKeyboardVisible()
        return nodeInspector.inspect(
            root = root,
            activePackageName = root?.packageName?.toString() ?: snapshot.activePackageName,
            windowTitle = snapshot.activeWindowTitle,
            timestampMillis = now,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            isKeyboardVisible = imeOpen
        )
    }

    override suspend fun awaitWindowChange(
        afterSequence: Long,
        timeoutMillis: Long
    ): AccessibilityWindowChangeSignal? {
        return withTimeoutOrNull(timeoutMillis.coerceIn(200L, 10_000L)) {
            NexaAccessibilityService.windowChanges
                .filter { it.sequence > afterSequence }
                .first()
        }
    }

    override fun latestEventSequence(): Long =
        NexaAccessibilityService.runtimeState.value.eventSequence

    private fun resolveDisplayDimensions(): Pair<Int, Int> {
        val ctx = context ?: return 1080 to 2400
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val dm = ctx.resources.displayMetrics
        val w = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            runCatching { wm.currentWindowMetrics.bounds.width() }.getOrDefault(dm.widthPixels)
        } else {
            dm.widthPixels
        }.coerceAtLeast(320)
        val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            runCatching { wm.currentWindowMetrics.bounds.height() }.getOrDefault(dm.heightPixels)
        } else {
            dm.heightPixels
        }.coerceAtLeast(480)
        return w to h
    }
}

open class AndroidScreenObserver(
    private val bridge: ScreenObservationBridge,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val screenshotBackend: ScreenshotCaptureBackend? = null,
    private val ocrProcessor: ScreenOcrProcessor? = null
) {
    private val snapshotCounter = AtomicLong(0L)
    // Strictly single active transient visual frame in RAM (reclaimed immediately on next observation).
    private val latestSparseVisualAttachment = AtomicReference<Pair<Long, AttachmentPayload>?>(null)

    open suspend fun observe(): ScreenObservation = withContext(workerDispatcher) {
        currentCoroutineContext().ensureActive()
        latestSparseVisualAttachment.set(null)
        val rawObs = stampSnapshot(bridge.currentObservation())
        enrichWithOcrIfNeeded(rawObs)
    }

    open suspend fun waitForChange(
        timeoutMillis: Long = 3_000L,
        expectedPackage: String? = null
    ): Pair<Boolean, ScreenObservation> = withContext(workerDispatcher) {
        currentCoroutineContext().ensureActive()
        val before = stampSnapshot(bridge.currentObservation())
        if (before.observationError != null && !before.isRestrictedWindow) {
            return@withContext false to before
        }
        if (!expectedPackage.isNullOrBlank() &&
            before.activePackageName.equals(expectedPackage, ignoreCase = true)
        ) {
            return@withContext true to enrichWithOcrIfNeeded(before)
        }

        val startSeq = bridge.latestEventSequence()
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceIn(300L, 10_000L)

        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(100L)
            val signal = bridge.awaitWindowChange(startSeq, minOf(remaining, 650L))
            val current = stampSnapshot(bridge.currentObservation())

            val packageMatched = !expectedPackage.isNullOrBlank() &&
                current.activePackageName.equals(expectedPackage, ignoreCase = true)
            val contentChanged = current.hierarchySignature.isNotBlank() &&
                current.hierarchySignature != before.hierarchySignature
            val packageChanged = current.activePackageName != before.activePackageName

            if (packageMatched || (expectedPackage.isNullOrBlank() && (packageChanged || contentChanged))) {
                return@withContext true to enrichWithOcrIfNeeded(current)
            }
            if (signal == null && remaining <= 150L) break
        }
        val finalObs = stampSnapshot(bridge.currentObservation())
        val changed = finalObs.hierarchySignature != before.hierarchySignature ||
            finalObs.activePackageName != before.activePackageName
        changed to enrichWithOcrIfNeeded(finalObs)
    }

    /**
     * Returns the transient sparse-hierarchy visual attachment captured during the
     * latest observation (if any) and clears the RAM reference immediately.
     */
    fun consumeLatestSparseVisualAttachment(expectedSnapshotId: Long? = null): AttachmentPayload? {
        val pair = latestSparseVisualAttachment.getAndSet(null) ?: return null
        if (expectedSnapshotId != null && pair.first != expectedSnapshotId) {
            return null
        }
        return pair.second
    }

    fun releaseCachedVisuals() {
        latestSparseVisualAttachment.set(null)
    }

    private suspend fun enrichWithOcrIfNeeded(obs: ScreenObservation): ScreenObservation {
        val backend = screenshotBackend ?: return obs
        if (!obs.hasSparseHierarchy && obs.nodes.isNotEmpty()) return obs
        if (obs.isDeviceLocked) return obs
        if (!backend.state.value.isSessionActive) return obs

        val frame = backend.captureVolatileFrameForAnalysis(includeVisionBase64 = true) ?: return obs
        try {
            val regions = ocrProcessor?.let { processor ->
                runCatching { processor.recognizeText(frame.bitmap) }.getOrDefault(emptyList())
            }.orEmpty()
            if (!frame.visionJpegBase64.isNullOrBlank() && obs.hasSparseHierarchy) {
                val payload = AttachmentPayload(
                    id = "sparse_frame_${obs.snapshotId}",
                    displayName = "screen_${obs.snapshotId}.jpg",
                    mimeType = "image/jpeg",
                    sourceUri = "memory://snapshot/${obs.snapshotId}",
                    imageBase64 = frame.visionJpegBase64,
                    byteSize = frame.visionJpegBase64.length.toLong(),
                    isSupported = true
                )
                latestSparseVisualAttachment.set(obs.snapshotId to payload)
            }
            return if (regions.isNotEmpty()) {
                SnapshotFusionHelper.fuse(obs, regions)
            } else {
                obs
            }
        } finally {
            runCatching {
                if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            }
        }
    }

    private fun stampSnapshot(obs: ScreenObservation): ScreenObservation {
        val id = if (obs.snapshotId > 0L) obs.snapshotId else snapshotCounter.incrementAndGet()
        return obs.copy(snapshotId = id)
    }

    fun toCompactJson(obs: ScreenObservation): JSONObject {
        val root = JSONObject()
            .put("snapshotId", obs.snapshotId)
            .put("timestampMillis", obs.timestampMillis)
            .put("activePackageName", obs.activePackageName ?: JSONObject.NULL)
            .put("windowTitle", obs.windowTitle ?: JSONObject.NULL)
            .put("screenWidth", obs.screenWidthPx)
            .put("screenHeight", obs.screenHeightPx)
            .put("isInteractive", obs.isInteractive)
            .put("isDeviceLocked", obs.isDeviceLocked)
            .put("isRestrictedWindow", obs.isRestrictedWindow)
            .put("keyboardVisible", obs.isKeyboardVisible)
            .put("isLoadingLikely", obs.isLoadingLikely)
            .put("hasSparseHierarchy", obs.hasSparseHierarchy)

        if (obs.dialogType != WindowDialogType.NONE) {
            root.put("dialogType", obs.dialogType.name)
        }
        if (obs.screenSummary.isNotBlank()) {
            root.put("screenSummary", obs.screenSummary)
        }
        if (obs.observationError != null) {
            root.put("error", obs.observationError)
        }

        val nodesArr = JSONArray()
        obs.nodes.forEach { node ->
            val obj = JSONObject()
                .put("index", node.index)
                .put("role", node.role)
                .put("bounds", node.bounds.toCompactString())
            node.text?.let { obj.put("text", it) }
            node.contentDescription?.let { obj.put("desc", it) }
            node.hintText?.let { obj.put("hint", it) }
            node.viewId?.let { obj.put("viewId", it) }
            node.parentIndex?.let { obj.put("parentIndex", it) }
            if (node.clickable) obj.put("clickable", true)
            if (node.longClickable) obj.put("longClickable", true)
            if (node.editable) obj.put("editable", true)
            if (node.scrollable) obj.put("scrollable", true)
            if (node.checkable) obj.put("checkable", true).put("checked", node.checked)
            if (node.selected) obj.put("selected", true)
            if (node.focused) obj.put("focused", true)
            if (!node.enabled) obj.put("enabled", false)
            if (node.source != UiNodeSource.ACCESSIBILITY) obj.put("source", node.source.name)
            if (node.isSensitiveRedacted) obj.put("sensitiveRedacted", true)
            nodesArr.put(obj)
        }
        root.put("nodeCount", obs.nodes.size)
        root.put("totalNodesVisited", obs.totalNodesVisited)
        root.put("truncated", obs.truncatedByLimit)
        root.put("nodes", nodesArr)

        if (obs.ocrRegions.isNotEmpty()) {
            root.put("ocrRegionCount", obs.ocrRegions.size)
        }
        return root
    }
}
