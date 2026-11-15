package com.example.android.accessibility

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.domain.model.ObservedUiNode
import com.example.domain.model.ScreenObservation
import com.example.domain.model.ScrollDirection
import com.example.domain.model.TargetResolutionSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class InteractionDispatchResult(
    val executed: Boolean,
    val detail: String,
    val resolvedTargetLabel: String? = null,
    val errorCode: String? = null,
    val staleNodeRefreshed: Boolean = false
)

sealed interface TargetResolutionOutcome {
    data class Resolved(val node: ObservedUiNode) : TargetResolutionOutcome
    data class Ambiguous(val candidates: List<ObservedUiNode>, val reason: String) : TargetResolutionOutcome
    data class NotFound(val reason: String) : TargetResolutionOutcome
}

interface InteractionBackend {
    suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation): InteractionDispatchResult
    suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation): InteractionDispatchResult
    suspend fun tapCoordinates(x: Float, y: Float): InteractionDispatchResult
    suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation): InteractionDispatchResult
    suspend fun scroll(direction: ScrollDirection, targetViewId: String?): InteractionDispatchResult
    suspend fun pressBack(): InteractionDispatchResult
    suspend fun pressHome(): InteractionDispatchResult
    suspend fun pressEnter(): InteractionDispatchResult
}

class LiveAccessibilityInteractionBackend(
    private val context: Context,
    private val inspector: AccessibilityNodeInspector = AccessibilityNodeInspector()
) : InteractionBackend {

    override suspend fun tapElement(
        spec: TargetResolutionSpec,
        cachedObservation: ScreenObservation
    ): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")

        return executeWithStaleRefresh(service, spec, cachedObservation) { node, matched ->
            if (!node.isEnabled) {
                return@executeWithStaleRefresh false to "Target ${describeNode(matched)} is currently disabled."
            }
            val targetNode = findClickableNodeOrAncestor(node)
            val nodeClicked = targetNode?.let {
                runCatching { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            } ?: false
            if (nodeClicked) {
                true to "Performed ACTION_CLICK on ${describeNode(matched)}."
            } else {
                val rect = Rect().also { runCatching { node.getBoundsInScreen(it) } }
                if (rect.width() > 0 && rect.height() > 0) {
                    val gestured = service.dispatchTap(rect.exactCenterX(), rect.exactCenterY())
                    if (gestured) {
                        true to "Dispatched tap gesture at (${rect.centerX()}, ${rect.centerY()}) on ${describeNode(matched)}."
                    } else {
                        false to "Click action and tap gesture both failed for ${describeNode(matched)}."
                    }
                } else {
                    false to "Target ${describeNode(matched)} is not clickable and has empty bounds."
                }
            }
        }
    }

    override suspend fun longPressElement(
        spec: TargetResolutionSpec,
        cachedObservation: ScreenObservation
    ): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")

        return executeWithStaleRefresh(service, spec, cachedObservation) { node, matched ->
            val longClickable = findLongClickableNodeOrAncestor(node)
            val nodeActed = longClickable?.let {
                runCatching { it.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
            } ?: false
            if (nodeActed) {
                true to "Performed ACTION_LONG_CLICK on ${describeNode(matched)}."
            } else {
                val rect = Rect().also { runCatching { node.getBoundsInScreen(it) } }
                if (rect.width() > 0 && rect.height() > 0) {
                    val gestured = service.dispatchLongPress(rect.exactCenterX(), rect.exactCenterY())
                    if (gestured) {
                        true to "Dispatched long-press gesture at (${rect.centerX()}, ${rect.centerY()}) on ${describeNode(matched)}."
                    } else {
                        false to "Long-press action and gesture failed for ${describeNode(matched)}."
                    }
                } else {
                    false to "Target ${describeNode(matched)} cannot be long-pressed."
                }
            }
        }
    }

    override suspend fun tapCoordinates(x: Float, y: Float): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")
        val metrics = context.resources.displayMetrics
        if (x < 0f || y < 0f || x > metrics.widthPixels.toFloat() || y > metrics.heightPixels.toFloat()) {
            return InteractionDispatchResult(
                executed = false,
                detail = "Coordinates (${x.toInt()}, ${y.toInt()}) are outside screen bounds (${metrics.widthPixels}x${metrics.heightPixels}).",
                errorCode = "OUT_OF_BOUNDS"
            )
        }
        val ok = service.dispatchTap(x, y)
        return InteractionDispatchResult(
            executed = ok,
            detail = if (ok) "Dispatched tap gesture at (${x.toInt()}, ${y.toInt()})." else "Tap gesture at (${x.toInt()}, ${y.toInt()}) was cancelled or failed.",
            errorCode = if (ok) null else "GESTURE_FAILED"
        )
    }

    override suspend fun typeText(
        spec: TargetResolutionSpec?,
        text: String,
        cachedObservation: ScreenObservation
    ): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")

        val effectiveSpec = spec ?: TargetResolutionSpec(editableOnly = true)
        return executeWithStaleRefresh(service, effectiveSpec, cachedObservation, preferEditable = true) { node, matched ->
            val editableNode = findEditableNode(node)
                ?: return@executeWithStaleRefresh false to "Resolved ${describeNode(matched)}, which is not an editable text field."

            if (!editableNode.isEnabled) {
                return@executeWithStaleRefresh false to "Editable field ${describeNode(matched)} is currently disabled."
            }

            val actions = runCatching { editableNode.actionList }.getOrNull().orEmpty()
            val explicitlyLacksSetText = actions.isNotEmpty() &&
                actions.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

            runCatching { editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            runCatching { editableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            delay(120)

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = runCatching {
                editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)

            if (ok) {
                val safeShown = if (matched.isSensitiveRedacted || matched.isPassword) "[REDACTED_SENSITIVE]" else "'$text'"
                true to "Entered $safeShown into ${describeNode(matched)}."
            } else if (explicitlyLacksSetText) {
                false to "Editable node ${describeNode(matched)} does not advertise ACTION_SET_TEXT on this screen."
            } else {
                false to "ACTION_SET_TEXT failed on ${describeNode(matched)}."
            }
        }
    }

    override suspend fun scroll(direction: ScrollDirection, targetViewId: String?): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")

        val root = service.getSafeRootNode()
        val scrollableNode = root?.let { findScrollableNode(it, targetViewId) }
        val forward = direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT
        if (scrollableNode != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            val ok = runCatching { scrollableNode.performAction(action) }.getOrDefault(false)
            if (ok) {
                return InteractionDispatchResult(
                    executed = true,
                    detail = "Scrolled ${direction.apiValue} via accessibility container action."
                )
            }
        }

        // Gesture fallback
        val metrics = context.resources.displayMetrics
        val cx = metrics.widthPixels * 0.5f
        val cy = metrics.heightPixels * 0.5f
        val (sx, sy, ex, ey) = when (direction) {
            ScrollDirection.DOWN -> listOf(cx, metrics.heightPixels * 0.72f, cx, metrics.heightPixels * 0.28f)
            ScrollDirection.UP -> listOf(cx, metrics.heightPixels * 0.28f, cx, metrics.heightPixels * 0.72f)
            ScrollDirection.RIGHT -> listOf(metrics.widthPixels * 0.8f, cy, metrics.widthPixels * 0.2f, cy)
            ScrollDirection.LEFT -> listOf(metrics.widthPixels * 0.2f, cy, metrics.widthPixels * 0.8f, cy)
        }
        val gestured = service.dispatchSwipe(sx, sy, ex, ey)
        return InteractionDispatchResult(
            executed = gestured,
            detail = if (gestured) "Swiped ${direction.apiValue} via gesture dispatch." else "Scroll ${direction.apiValue} failed.",
            errorCode = if (gestured) null else "SCROLL_FAILED"
        )
    }

    override suspend fun pressBack(): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")
        val ok = service.performBackAction()
        return InteractionDispatchResult(
            executed = ok,
            detail = if (ok) "Triggered global Back navigation." else "Global Back navigation failed.",
            errorCode = if (ok) null else "GLOBAL_ACTION_FAILED"
        )
    }

    override suspend fun pressHome(): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")
        val ok = service.performHomeAction()
        return InteractionDispatchResult(
            executed = ok,
            detail = if (ok) "Triggered global Home navigation." else "Global Home navigation failed.",
            errorCode = if (ok) null else "GLOBAL_ACTION_FAILED"
        )
    }

    override suspend fun pressEnter(): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")
        val root = service.getSafeRootNode()
            ?: return InteractionDispatchResult(false, "No active window available for Enter/Submit.", errorCode = "NO_WINDOW")

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstFocusedOrEditable(root)
        if (focused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val imeOk = runCatching {
                focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }.getOrDefault(false)
            if (imeOk) {
                return InteractionDispatchResult(
                    executed = true,
                    detail = "Submitted input using ACTION_IME_ENTER."
                )
            }
        }

        // Fallback: look for visible Search / Go / Submit / Done button in current window
        val submitNode = findSubmitActionButton(root)
        if (submitNode != null) {
            val clicked = runCatching { submitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            if (clicked) {
                return InteractionDispatchResult(
                    executed = true,
                    detail = "Tapped on-screen submit/search action button."
                )
            }
        }

        return InteractionDispatchResult(
            executed = false,
            detail = "No focused IME input or explicit submit button accepted Enter on the current screen.",
            errorCode = "ENTER_UNSUPPORTED"
        )
    }

    private suspend fun executeWithStaleRefresh(
        service: NexaAccessibilityService,
        spec: TargetResolutionSpec,
        cachedObservation: ScreenObservation,
        preferEditable: Boolean = false,
        block: suspend (AccessibilityNodeInfo, ObservedUiNode) -> Pair<Boolean, String>
    ): InteractionDispatchResult {
        var refreshed = false
        for (attempt in 0..1) {
            currentCoroutineContext().ensureActive()
            val root = service.getSafeRootNode()
                ?: return InteractionDispatchResult(false, "Active window root is unavailable.", errorCode = "NO_WINDOW")
            val obs = if (attempt == 0 && cachedObservation.nodes.isNotEmpty()) {
                cachedObservation
            } else {
                refreshed = true
                inspector.inspect(root, root.packageName?.toString(), null)
            }
            when (val resolution = AccessibilityInteractionController.resolveTargetNodeChecked(obs, spec, preferEditable)) {
                is TargetResolutionOutcome.NotFound -> {
                    if (attempt == 0) {
                        delay(150)
                        continue
                    }
                    return InteractionDispatchResult(
                        executed = false,
                        detail = "No UI element matched ${describeSpec(spec)}.",
                        errorCode = "TARGET_NOT_FOUND",
                        staleNodeRefreshed = refreshed
                    )
                }
                is TargetResolutionOutcome.Ambiguous -> {
                    return InteractionDispatchResult(
                        executed = false,
                        detail = resolution.reason,
                        errorCode = "AMBIGUOUS_TARGET",
                        staleNodeRefreshed = refreshed
                    )
                }
                is TargetResolutionOutcome.Resolved -> {
                    val matchedDto = resolution.node
                    val liveNode = findLiveNodeMatching(root, matchedDto)
                    if (liveNode == null) {
                        if (attempt == 0) {
                            delay(150)
                            continue
                        }
                        return InteractionDispatchResult(
                            executed = false,
                            detail = "Matched ${describeNode(matchedDto)} in snapshot, but live accessibility node became stale.",
                            resolvedTargetLabel = describeNode(matchedDto),
                            errorCode = "STALE_NODE",
                            staleNodeRefreshed = refreshed
                        )
                    }
                    val (ok, msg) = block(liveNode, matchedDto)
                    if (ok || attempt == 1) {
                        return InteractionDispatchResult(
                            executed = ok,
                            detail = msg,
                            resolvedTargetLabel = describeNode(matchedDto),
                            errorCode = if (ok) null else "ACTION_FAILED",
                            staleNodeRefreshed = refreshed
                        )
                    }
                    delay(150)
                }
            }
        }
        return InteractionDispatchResult(false, "Interaction failed after stale-node refresh.", errorCode = "ACTION_FAILED", staleNodeRefreshed = true)
    }

    private fun findLiveNodeMatching(root: AccessibilityNodeInfo, target: ObservedUiNode): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var bestFallback: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && visited < 220) {
            val current = queue.removeFirst()
            visited++
            val rect = Rect().also { runCatching { current.getBoundsInScreen(it) } }
            val currentId = runCatching { current.viewIdResourceName?.substringAfterLast('/') }.getOrNull()
            val currentText = runCatching { current.text?.toString()?.trim() }.getOrNull()

            val sameBounds = rect.left == target.bounds.left &&
                rect.top == target.bounds.top &&
                rect.right == target.bounds.right &&
                rect.bottom == target.bounds.bottom

            if (sameBounds) {
                val idMatches = target.viewId == null || target.viewId == currentId
                val textMatches = target.isSensitiveRedacted || target.text == null || currentText?.startsWith(target.text.removeSuffix("…")) == true
                if (idMatches && textMatches) return current
                if (bestFallback == null) bestFallback = current
            }
            val childCount = runCatching { current.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                runCatching { current.getChild(i) }.getOrNull()?.let { queue.addLast(it) }
            }
        }
        return bestFallback
    }

    private fun findClickableNodeOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 5) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = runCatching { cur.parent }.getOrNull()
            hops++
        }
        return if (node.isClickable && node.isEnabled) node else null
    }

    private fun findLongClickableNodeOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 5) {
            if (cur.isLongClickable && cur.isEnabled) return cur
            cur = runCatching { cur.parent }.getOrNull()
            hops++
        }
        return if (node.isLongClickable && node.isEnabled) node else null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < 20) {
            val cur = queue.removeFirst()
            visited++
            if (cur.isEditable) return cur
            val count = runCatching { cur.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo, targetViewId: String?): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var firstScrollable: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && visited < 180) {
            val cur = queue.removeFirst()
            visited++
            val vid = runCatching { cur.viewIdResourceName?.substringAfterLast('/') }.getOrNull()
            if (cur.isScrollable) {
                if (!targetViewId.isNullOrBlank() && vid == targetViewId) return cur
                if (firstScrollable == null) firstScrollable = cur
            }
            val count = runCatching { cur.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return firstScrollable
    }

    private fun findFirstFocusedOrEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var firstEditable: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && visited < 160) {
            val cur = queue.removeFirst()
            visited++
            if (cur.isFocused && cur.isEditable) return cur
            if (firstEditable == null && cur.isEditable) firstEditable = cur
            val count = runCatching { cur.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return firstEditable
    }

    private fun findSubmitActionButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val keywords = listOf("search", "go", "submit", "done")
        while (queue.isNotEmpty() && visited < 160) {
            val cur = queue.removeFirst()
            visited++
            if (cur.isClickable && cur.isEnabled && !cur.isEditable) {
                val label = listOfNotNull(
                    cur.text?.toString(),
                    cur.contentDescription?.toString(),
                    cur.viewIdResourceName?.substringAfterLast('/')
                ).joinToString(" ").lowercase()
                if (keywords.any { label == it || label.contains(it) }) {
                    return cur
                }
            }
            val count = runCatching { cur.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun describeNode(node: ObservedUiNode): String =
        node.text?.let { "'$it'" }
            ?: node.contentDescription?.let { "'$it'" }
            ?: node.viewId?.let { "#$it" }
            ?: "${node.role}[${node.index}]"

    private fun describeSpec(spec: TargetResolutionSpec): String = buildList {
        spec.nodeIndex?.let { add("node_index=$it") }
        spec.viewId?.let { add("view_id='$it'") }
        spec.text?.let { add("text='$it'") }
        spec.contentDescription?.let { add("content_description='$it'") }
        spec.role?.let { add("role='$it'") }
        if (spec.editableOnly) add("editable=true")
    }.joinToString(", ").ifBlank { "any element" }
}

open class AccessibilityInteractionController(
    private val backend: InteractionBackend
) {

    open suspend fun tapElement(spec: TargetResolutionSpec, observation: ScreenObservation): InteractionDispatchResult =
        backend.tapElement(spec, observation)

    open suspend fun longPressElement(spec: TargetResolutionSpec, observation: ScreenObservation): InteractionDispatchResult =
        backend.longPressElement(spec, observation)

    open suspend fun tapCoordinates(x: Float, y: Float): InteractionDispatchResult =
        backend.tapCoordinates(x, y)

    open suspend fun typeText(spec: TargetResolutionSpec?, text: String, observation: ScreenObservation): InteractionDispatchResult =
        backend.typeText(spec, text, observation)

    open suspend fun scroll(direction: ScrollDirection, targetViewId: String?): InteractionDispatchResult =
        backend.scroll(direction, targetViewId)

    open suspend fun pressBack(): InteractionDispatchResult =
        backend.pressBack()

    open suspend fun pressHome(): InteractionDispatchResult =
        backend.pressHome()

    open suspend fun pressEnter(): InteractionDispatchResult =
        backend.pressEnter()

    companion object {
        fun resolveTargetNode(
            observation: ScreenObservation,
            spec: TargetResolutionSpec,
            preferEditable: Boolean = false
        ): ObservedUiNode? = when (val outcome = resolveTargetNodeChecked(observation, spec, preferEditable)) {
            is TargetResolutionOutcome.Resolved -> outcome.node
            is TargetResolutionOutcome.Ambiguous -> null
            is TargetResolutionOutcome.NotFound -> null
        }

        fun resolveTargetNodeChecked(
            observation: ScreenObservation,
            spec: TargetResolutionSpec,
            preferEditable: Boolean = false
        ): TargetResolutionOutcome {
            val rawNodes = observation.nodes
            if (rawNodes.isEmpty()) {
                return TargetResolutionOutcome.NotFound("No UI nodes observed on screen.")
            }

            // Explicit nodeIndex resolves unambiguously when present
            spec.nodeIndex?.let { idx ->
                val byIndex = rawNodes.firstOrNull { it.index == idx }
                if (byIndex != null) return TargetResolutionOutcome.Resolved(byIndex)
            }

            val interactiveNodes = rawNodes.filter {
                it.enabled && (it.bounds.width > 0 || it.bounds.height > 0 || it.editable || it.clickable)
            }.ifEmpty { rawNodes.filter { it.enabled } }.ifEmpty { rawNodes }

            val candidates = if (preferEditable || spec.editableOnly) {
                interactiveNodes.filter { it.editable || it.role.contains("Field", ignoreCase = true) }
                    .ifEmpty { interactiveNodes }
            } else {
                interactiveNodes
            }

            // Priority 1: View ID
            spec.viewId?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { vid ->
                val cleanVid = vid.substringAfterLast('/')
                val exactId = candidates.filter { normOrNull(it.viewId?.substringAfterLast('/')) == cleanVid }
                pickUniqueOrAmbiguous(exactId, "view_id='$vid'")?.let { return it }
                val partialId = candidates.filter { normOrNull(it.viewId)?.contains(cleanVid) == true }
                pickUniqueOrAmbiguous(partialId, "view_id~='$vid'")?.let { return it }
            }

            // Priority 2: Exact contentDescription
            spec.contentDescription?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { q ->
                val exactDesc = candidates.filter { normOrNull(it.contentDescription) == q }
                pickUniqueOrAmbiguous(exactDesc, "content_description='$q'")?.let { return it }
            }

            // Priority 3: Exact visible text / hint
            spec.text?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { q ->
                val exactText = candidates.filter { normOrNull(it.text) == q || normOrNull(it.hintText) == q }
                pickUniqueOrAmbiguous(exactText, "text='$q'")?.let { return it }

                val exactDescFallback = candidates.filter { normOrNull(it.contentDescription) == q }
                pickUniqueOrAmbiguous(exactDescFallback, "text/desc='$q'")?.let { return it }
            }

            // Priority 4: Partial text / hint / contentDescription
            spec.text?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { q ->
                val partialText = candidates.filter {
                    normOrNull(it.text)?.contains(q) == true ||
                        normOrNull(it.hintText)?.contains(q) == true
                }
                pickUniqueOrAmbiguous(partialText, "partial text '$q'")?.let { return it }

                val partialDesc = candidates.filter { normOrNull(it.contentDescription)?.contains(q) == true }
                pickUniqueOrAmbiguous(partialDesc, "partial desc '$q'")?.let { return it }
            }

            spec.contentDescription?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { q ->
                val partialDesc = candidates.filter { normOrNull(it.contentDescription)?.contains(q) == true }
                pickUniqueOrAmbiguous(partialDesc, "partial content_description '$q'")?.let { return it }
            }

            // Priority 5: Role + structural context
            spec.role?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { r ->
                val roleMatches = candidates.filter { normOrNull(it.role) == r }
                pickUniqueOrAmbiguous(roleMatches, "role='$r'")?.let { return it }
            }

            if (preferEditable || spec.editableOnly) {
                val focusedOrEditable = candidates.firstOrNull { it.focused && it.editable }
                    ?: candidates.firstOrNull { it.editable }
                if (focusedOrEditable != null) {
                    return TargetResolutionOutcome.Resolved(focusedOrEditable)
                }
            }

            return TargetResolutionOutcome.NotFound("No matching enabled UI element found.")
        }

        private fun pickUniqueOrAmbiguous(
            matches: List<ObservedUiNode>,
            selectorDesc: String
        ): TargetResolutionOutcome? {
            if (matches.isEmpty()) return null
            if (matches.size == 1) return TargetResolutionOutcome.Resolved(matches.first())

            // Prefer clickable or editable nodes if only one is interactive
            val actionable = matches.filter { it.clickable || it.editable }
            if (actionable.size == 1) {
                return TargetResolutionOutcome.Resolved(actionable.first())
            }
            val pool = actionable.ifEmpty { matches }
            // Check if all tied items are identical nested duplicates or genuinely distinct targets
            val distinctTexts = pool.map { "${normOrNull(it.text)}|${normOrNull(it.contentDescription)}|${it.viewId}" }.toSet()
            return TargetResolutionOutcome.Ambiguous(
                candidates = pool.take(5),
                reason = "Ambiguous UI target for $selectorDesc: ${pool.size} matching nodes (${distinctTexts.size} distinct). Specify node_index or view_id."
            )
        }

        private fun norm(value: String): String =
            value.trim().replace(Regex("\\s+"), " ").lowercase()

        private fun normOrNull(value: String?): String? =
            value?.let(::norm)?.takeIf { it.isNotEmpty() }
    }
}
