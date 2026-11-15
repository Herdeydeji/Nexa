package com.example.android.accessibility

import com.example.android.runtime.AndroidPermissionManager
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.ScreenObservation
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
    private val nodeInspector: AccessibilityNodeInspector = AccessibilityNodeInspector()
) : ScreenObservationBridge {

    override fun currentObservation(): ScreenObservation {
        val now = System.currentTimeMillis()
        val perm = permissionManager.inspect()
        val snapshot = NexaAccessibilityService.runtimeState.value
        if (perm.accessibilityState != AccessibilityRuntimeState.SERVICE_ENABLED) {
            return ScreenObservation(
                timestampMillis = now,
                activePackageName = snapshot.activePackageName,
                windowTitle = snapshot.activeWindowTitle,
                isInteractive = false,
                isDeviceLocked = perm.isDeviceLocked,
                isRestrictedWindow = perm.isDeviceLocked,
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
                observationError = "Temporarily unavailable — Nexa Accessibility Service instance is not bound."
            )

        val root = service.getSafeRootNode()
        return nodeInspector.inspect(
            root = root,
            activePackageName = root?.packageName?.toString() ?: snapshot.activePackageName,
            windowTitle = snapshot.activeWindowTitle,
            timestampMillis = now
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
}

open class AndroidScreenObserver(
    private val bridge: ScreenObservationBridge,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    open suspend fun observe(): ScreenObservation = withContext(workerDispatcher) {
        currentCoroutineContext().ensureActive()
        bridge.currentObservation()
    }

    open suspend fun waitForChange(
        timeoutMillis: Long = 3_000L,
        expectedPackage: String? = null
    ): Pair<Boolean, ScreenObservation> = withContext(workerDispatcher) {
        currentCoroutineContext().ensureActive()
        val before = bridge.currentObservation()
        if (before.observationError != null && !before.isRestrictedWindow) {
            return@withContext false to before
        }
        if (!expectedPackage.isNullOrBlank() &&
            before.activePackageName.equals(expectedPackage, ignoreCase = true)
        ) {
            return@withContext true to before
        }

        val startSeq = bridge.latestEventSequence()
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceIn(300L, 10_000L)

        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(100L)
            val signal = bridge.awaitWindowChange(startSeq, minOf(remaining, 650L))
            val current = bridge.currentObservation()

            val packageMatched = !expectedPackage.isNullOrBlank() &&
                current.activePackageName.equals(expectedPackage, ignoreCase = true)
            val contentChanged = current.hierarchySignature.isNotBlank() &&
                current.hierarchySignature != before.hierarchySignature
            val packageChanged = current.activePackageName != before.activePackageName

            if (packageMatched || (expectedPackage.isNullOrBlank() && (packageChanged || contentChanged))) {
                return@withContext true to current
            }
            if (signal == null && remaining <= 150L) break
        }
        val finalObs = bridge.currentObservation()
        val changed = finalObs.hierarchySignature != before.hierarchySignature ||
            finalObs.activePackageName != before.activePackageName
        changed to finalObs
    }

    fun toCompactJson(obs: ScreenObservation): JSONObject {
        val root = JSONObject()
            .put("timestampMillis", obs.timestampMillis)
            .put("activePackageName", obs.activePackageName ?: JSONObject.NULL)
            .put("windowTitle", obs.windowTitle ?: JSONObject.NULL)
            .put("isInteractive", obs.isInteractive)
            .put("isDeviceLocked", obs.isDeviceLocked)
            .put("isRestrictedWindow", obs.isRestrictedWindow)

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
            if (node.clickable) obj.put("clickable", true)
            if (node.editable) obj.put("editable", true)
            if (node.scrollable) obj.put("scrollable", true)
            if (node.checkable) obj.put("checkable", true).put("checked", node.checked)
            if (node.focused) obj.put("focused", true)
            if (!node.enabled) obj.put("enabled", false)
            if (node.isSensitiveRedacted) obj.put("sensitiveRedacted", true)
            nodesArr.put(obj)
        }
        root.put("nodeCount", obs.nodes.size)
        root.put("totalNodesVisited", obs.totalNodesVisited)
        root.put("truncated", obs.truncatedByLimit)
        root.put("nodes", nodesArr)
        return root
    }
}
