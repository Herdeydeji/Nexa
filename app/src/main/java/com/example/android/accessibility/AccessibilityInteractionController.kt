package com.example.android.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.domain.model.GroundingMatchExplanation
import com.example.domain.model.ObservedUiNode
import com.example.domain.model.ScreenObservation
import com.example.domain.model.ScrollDirection
import com.example.domain.model.TargetResolutionSpec
import com.example.domain.model.TextEditMode
import com.example.domain.model.UiNodeSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class InteractionDispatchResult(
    val executed: Boolean,
    val detail: String,
    val resolvedTargetLabel: String? = null,
    val errorCode: String? = null,
    val staleNodeRefreshed: Boolean = false,
    val explanation: GroundingMatchExplanation? = null,
    val ambiguousCandidates: List<ObservedUiNode> = emptyList()
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

    suspend fun doubleTapElement(
        spec: TargetResolutionSpec,
        cachedObservation: ScreenObservation
    ): InteractionDispatchResult = InteractionDispatchResult(
        executed = false,
        detail = "double_tap_element is unsupported on this backend.",
        errorCode = "UNSUPPORTED"
    )

    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L
    ): InteractionDispatchResult = InteractionDispatchResult(
        executed = false,
        detail = "swipe is unsupported on this backend.",
        errorCode = "UNSUPPORTED"
    )

    suspend fun dragAndDrop(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 750L
    ): InteractionDispatchResult = InteractionDispatchResult(
        executed = false,
        detail = "drag_and_drop is unsupported on this backend.",
        errorCode = "UNSUPPORTED"
    )

    suspend fun editTextField(
        spec: TargetResolutionSpec?,
        mode: TextEditMode,
        text: String?,
        cachedObservation: ScreenObservation
    ): InteractionDispatchResult = when (mode) {
        TextEditMode.REPLACE -> typeText(spec, text.orEmpty(), cachedObservation)
        else -> InteractionDispatchResult(
            executed = false,
            detail = "edit_text_field($mode) is unsupported on this backend.",
            errorCode = "UNSUPPORTED"
        )
    }
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

        return executeWithStaleRefresh(service, spec, cachedObservation) { node, matched, explanation ->
            if (explanation.overlapDetected) {
                return@executeWithStaleRefresh InteractionDispatchResult(
                    executed = false,
                    detail = "Target ${describeNode(matched)} appears covered by another overlapping overlay or dialog.",
                    resolvedTargetLabel = describeNode(matched),
                    errorCode = "ELEMENT_OBSTRUCTED",
                    explanation = explanation
                )
            }
            if (matched.source == UiNodeSource.OCR_FUSED || node == null) {
                val rect = matched.bounds
                if (rect.width > 0 && rect.height > 0) {
                    val ok = service.dispatchTap(rect.centerX.toFloat(), rect.centerY.toFloat())
                    return@executeWithStaleRefresh InteractionDispatchResult(
                        executed = ok,
                        detail = if (ok) {
                            "Tapped OCR visual target ${describeNode(matched)} at (${rect.centerX}, ${rect.centerY})."
                        } else {
                            "Gesture tap failed on OCR target ${describeNode(matched)}."
                        },
                        resolvedTargetLabel = describeNode(matched),
                        errorCode = if (ok) null else "GESTURE_FAILED",
                        explanation = explanation
                    )
                }
                return@executeWithStaleRefresh InteractionDispatchResult(
                    executed = false,
                    detail = "Target ${describeNode(matched)} has empty screen bounds.",
                    errorCode = "TARGET_NOT_FOUND",
                    explanation = explanation
                )
            }

            if (!node.isEnabled) {
                return@executeWithStaleRefresh InteractionDispatchResult(
                    executed = false,
                    detail = "Target ${describeNode(matched)} is currently disabled.",
                    resolvedTargetLabel = describeNode(matched),
                    errorCode = "ELEMENT_DISABLED",
                    explanation = explanation
                )
            }
            val targetNode = findClickableNodeOrAncestor(node)
            val nodeClicked = targetNode?.let {
                runCatching { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            } ?: false
            if (nodeClicked) {
                InteractionDispatchResult(
                    executed = true,
                    detail = "Performed ACTION_CLICK on ${describeNode(matched)}.",
                    resolvedTargetLabel = describeNode(matched),
                    explanation = explanation
                )
            } else {
                val rect = Rect().also { runCatching { node.getBoundsInScreen(it) } }
                if (rect.width() > 0 && rect.height() > 0) {
                    val gestured = service.dispatchTap(rect.exactCenterX(), rect.exactCenterY())
                    if (gestured) {
                        InteractionDispatchResult(
                            executed = true,
                            detail = "Dispatched tap gesture at (${rect.centerX()}, ${rect.centerY()}) on ${describeNode(matched)}.",
                            resolvedTargetLabel = describeNode(matched),
                            explanation = explanation
                        )
                    } else {
                        InteractionDispatchResult(
                            executed = false,
                            detail = "Click action and tap gesture both failed for ${describeNode(matched)}.",
                            resolvedTargetLabel = describeNode(matched),
                            errorCode = "ELEMENT_NOT_CLICKABLE",
                            explanation = explanation
                        )
                    }
                } else {
                    InteractionDispatchResult(
                        executed = false,
                        detail = "Target ${describeNode(matched)} is not clickable and has empty bounds.",
                        resolvedTargetLabel = describeNode(matched),
                        errorCode = "ELEMENT_NOT_CLICKABLE",
                        explanation = explanation
                    )
                }
            }
        }
    }

    override suspend fun doubleTapElement(
        spec: TargetResolutionSpec,
        cachedObservation: ScreenObservation
    ): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")

        return executeWithStaleRefresh(service, spec, cachedObservation) { node, matched, explanation ->
            val rect = if (node != null) {
                Rect().also { runCatching { node.getBoundsInScreen(it) } }
            } else {
                Rect(matched.bounds.left, matched.bounds.top, matched.bounds.right, matched.bounds.bottom)
            }
            if (rect.width() <= 0 || rect.height() <= 0) {
                return@executeWithStaleRefresh InteractionDispatchResult(
                    executed = false,
                    detail = "Target ${describeNode(matched)} has empty bounds for double-tap.",
                    errorCode = "ELEMENT_NOT_CLICKABLE",
                    explanation = explanation
                )
            }
            val ok = service.dispatchDoubleTap(rect.exactCenterX(), rect.exactCenterY())
            InteractionDispatchResult(
                executed = ok,
                detail = if (ok) {
                    "Dispatched double-tap at (${rect.centerX()}, ${rect.centerY()}) on ${describeNode(matched)}."
                } else {
                    "Double-tap gesture failed on ${describeNode(matched)}."
                },
                resolvedTargetLabel = describeNode(matched),
                errorCode = if (ok) null else "GESTURE_FAILED",
                explanation = explanation
            )
        }
    }

    override suspend fun longPressElement(
        spec: TargetResolutionSpec,
        cachedObservation: ScreenObservation
    ): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")

        return executeWithStaleRefresh(service, spec, cachedObservation) { node, matched, explanation ->
            val longClickable = node?.let(::findLongClickableNodeOrAncestor)
            val nodeActed = longClickable?.let {
                runCatching { it.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
            } ?: false
            if (nodeActed) {
                InteractionDispatchResult(
                    executed = true,
                    detail = "Performed ACTION_LONG_CLICK on ${describeNode(matched)}.",
                    resolvedTargetLabel = describeNode(matched),
                    explanation = explanation
                )
            } else {
                val rect = if (node != null) {
                    Rect().also { runCatching { node.getBoundsInScreen(it) } }
                } else {
                    Rect(matched.bounds.left, matched.bounds.top, matched.bounds.right, matched.bounds.bottom)
                }
                if (rect.width() > 0 && rect.height() > 0) {
                    val gestured = service.dispatchLongPress(rect.exactCenterX(), rect.exactCenterY())
                    InteractionDispatchResult(
                        executed = gestured,
                        detail = if (gestured) {
                            "Dispatched long-press gesture at (${rect.centerX()}, ${rect.centerY()}) on ${describeNode(matched)}."
                        } else {
                            "Long-press action and gesture failed for ${describeNode(matched)}."
                        },
                        resolvedTargetLabel = describeNode(matched),
                        errorCode = if (gestured) null else "ELEMENT_NOT_CLICKABLE",
                        explanation = explanation
                    )
                } else {
                    InteractionDispatchResult(
                        executed = false,
                        detail = "Target ${describeNode(matched)} cannot be long-pressed.",
                        errorCode = "ELEMENT_NOT_CLICKABLE",
                        explanation = explanation
                    )
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

    override suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")
        val metrics = context.resources.displayMetrics
        val maxW = metrics.widthPixels.toFloat()
        val maxH = metrics.heightPixels.toFloat()
        if (startX < 0f || startY < 0f || endX < 0f || endY < 0f ||
            startX > maxW || endX > maxW || startY > maxH || endY > maxH
        ) {
            return InteractionDispatchResult(
                executed = false,
                detail = "Swipe coordinates out of screen bounds (${metrics.widthPixels}x${metrics.heightPixels}).",
                errorCode = "OUT_OF_BOUNDS"
            )
        }
        val ok = service.dispatchSwipe(startX, startY, endX, endY, durationMs)
        return InteractionDispatchResult(
            executed = ok,
            detail = if (ok) {
                "Swiped from (${startX.toInt()}, ${startY.toInt()}) to (${endX.toInt()}, ${endY.toInt()})."
            } else {
                "Swipe gesture was cancelled or failed."
            },
            errorCode = if (ok) null else "GESTURE_FAILED"
        )
    }

    override suspend fun dragAndDrop(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")
        val metrics = context.resources.displayMetrics
        val maxW = metrics.widthPixels.toFloat()
        val maxH = metrics.heightPixels.toFloat()
        if (startX < 0f || startY < 0f || endX < 0f || endY < 0f ||
            startX > maxW || endX > maxW || startY > maxH || endY > maxH
        ) {
            return InteractionDispatchResult(
                executed = false,
                detail = "Drag coordinates out of screen bounds (${metrics.widthPixels}x${metrics.heightPixels}).",
                errorCode = "OUT_OF_BOUNDS"
            )
        }
        val ok = service.dispatchDragAndDrop(startX, startY, endX, endY, durationMs)
        return InteractionDispatchResult(
            executed = ok,
            detail = if (ok) {
                "Dragged from (${startX.toInt()}, ${startY.toInt()}) to (${endX.toInt()}, ${endY.toInt()})."
            } else {
                "Drag-and-drop gesture was cancelled or failed."
            },
            errorCode = if (ok) null else "GESTURE_FAILED"
        )
    }

    override suspend fun typeText(
        spec: TargetResolutionSpec?,
        text: String,
        cachedObservation: ScreenObservation
    ): InteractionDispatchResult = editTextField(spec, TextEditMode.REPLACE, text, cachedObservation)

    override suspend fun editTextField(
        spec: TargetResolutionSpec?,
        mode: TextEditMode,
        text: String?,
        cachedObservation: ScreenObservation
    ): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")

        val effectiveSpec = spec ?: TargetResolutionSpec(editableOnly = true)
        return executeWithStaleRefresh(service, effectiveSpec, cachedObservation, preferEditable = true) { node, matched, explanation ->
            val editableNode = node?.let(::findEditableNode)
                ?: return@executeWithStaleRefresh InteractionDispatchResult(
                    executed = false,
                    detail = "Resolved ${describeNode(matched)}, which is not an editable text field.",
                    errorCode = "TEXT_ENTRY_REJECTED",
                    explanation = explanation
                )

            if (!editableNode.isEnabled) {
                return@executeWithStaleRefresh InteractionDispatchResult(
                    executed = false,
                    detail = "Editable field ${describeNode(matched)} is currently disabled.",
                    errorCode = "ELEMENT_DISABLED",
                    explanation = explanation
                )
            }

            // Ensure focus cleanly before editing
            if (!editableNode.isFocused) {
                runCatching { editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                runCatching { editableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                delay(140)
            }

            val safeLabel = if (matched.isSensitiveRedacted || matched.isPassword) "[REDACTED_SENSITIVE]" else "'${text.orEmpty()}'"
            when (mode) {
                TextEditMode.SELECT_ALL -> {
                    val existingLen = editableNode.text?.length ?: 0
                    val selArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingLen)
                    }
                    val ok = runCatching {
                        editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                    }.getOrDefault(false)
                    return@executeWithStaleRefresh InteractionDispatchResult(
                        executed = ok,
                        detail = if (ok) "Selected all text ($existingLen chars) in ${describeNode(matched)}." else "Select-all failed on ${describeNode(matched)}.",
                        resolvedTargetLabel = describeNode(matched),
                        errorCode = if (ok) null else "TEXT_ENTRY_REJECTED",
                        explanation = explanation
                    )
                }

                TextEditMode.CLEAR -> {
                    val clearArgs = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    }
                    val ok = runCatching {
                        editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
                    }.getOrDefault(false)
                    return@executeWithStaleRefresh InteractionDispatchResult(
                        executed = ok,
                        detail = if (ok) "Cleared text field ${describeNode(matched)}." else "Failed to clear ${describeNode(matched)}.",
                        resolvedTargetLabel = describeNode(matched),
                        errorCode = if (ok) null else "TEXT_ENTRY_REJECTED",
                        explanation = explanation
                    )
                }

                TextEditMode.PASTE -> {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (!text.isNullOrEmpty() && clip != null) {
                        runCatching { clip.setPrimaryClip(ClipData.newPlainText("nexa_input", text)) }
                    }
                    val pasted = runCatching {
                        editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }.getOrDefault(false)
                    return@executeWithStaleRefresh InteractionDispatchResult(
                        executed = pasted,
                        detail = if (pasted) "Pasted $safeLabel into ${describeNode(matched)}." else "ACTION_PASTE unsupported on ${describeNode(matched)}.",
                        resolvedTargetLabel = describeNode(matched),
                        errorCode = if (pasted) null else "TEXT_ENTRY_REJECTED",
                        explanation = explanation
                    )
                }

                TextEditMode.APPEND,
                TextEditMode.REPLACE -> {
                    val targetPayload = if (mode == TextEditMode.APPEND) {
                        val existing = editableNode.text?.toString().orEmpty()
                        val ignoreHint = matched.hintText
                        val base = if (ignoreHint != null && existing == ignoreHint) "" else existing
                        base + text.orEmpty()
                    } else {
                        text.orEmpty()
                    }

                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetPayload)
                    }
                    var ok = runCatching {
                        editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }.getOrDefault(false)

                    // Fallback to select-all + clipboard paste if ACTION_SET_TEXT fails
                    if (!ok && targetPayload.isNotEmpty() && !(matched.isSensitiveRedacted || matched.isPassword)) {
                        val existingLen = editableNode.text?.length ?: 0
                        if (mode == TextEditMode.REPLACE && existingLen > 0) {
                            val selArgs = Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingLen)
                            }
                            runCatching { editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
                        }
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (clip != null) {
                            runCatching { clip.setPrimaryClip(ClipData.newPlainText("nexa_input", targetPayload)) }
                            ok = runCatching { editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
                        }
                    }

                    // Immediate read-back check
                    val refreshedOk = if (ok && !(matched.isSensitiveRedacted || matched.isPassword)) {
                        runCatching { editableNode.refresh() }
                        val readBack = editableNode.text?.toString().orEmpty()
                        targetPayload.isEmpty() || readBack.contains(text.orEmpty(), ignoreCase = true) || ok
                    } else {
                        ok
                    }

                    return@executeWithStaleRefresh InteractionDispatchResult(
                        executed = refreshedOk,
                        detail = if (refreshedOk) {
                            "Entered $safeLabel into ${describeNode(matched)}."
                        } else {
                            "Editable node ${describeNode(matched)} rejected text entry."
                        },
                        resolvedTargetLabel = describeNode(matched),
                        errorCode = if (refreshedOk) null else "TEXT_ENTRY_REJECTED",
                        explanation = explanation
                    )
                }
            }
        }
    }

    override suspend fun scroll(direction: ScrollDirection, targetViewId: String?): InteractionDispatchResult {
        val service = NexaAccessibilityService.currentInstance()
            ?: return InteractionDispatchResult(false, "Accessibility service is not connected.", errorCode = "SERVICE_DISCONNECTED")

        val root = service.getSafeRootNode()
        val scrollableNode = root?.let { findBestScrollableNode(it, direction, targetViewId) }
        val forward = direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT
        val beforeSample = scrollableNode?.let(::sampleChildSignature)

        if (scrollableNode != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            val ok = runCatching { scrollableNode.performAction(action) }.getOrDefault(false)
            if (ok) {
                delay(180)
                runCatching { scrollableNode.refresh() }
                val afterSample = sampleChildSignature(scrollableNode)
                if (afterSample != beforeSample) {
                    return InteractionDispatchResult(
                        executed = true,
                        detail = "Scrolled ${direction.apiValue} inside container and confirmed child movement."
                    )
                }
            }
        }

        // Targeted gesture swipe bounded to container rect (or display viewport)
        val containerRect = Rect()
        if (scrollableNode != null) {
            runCatching { scrollableNode.getBoundsInScreen(containerRect) }
        }
        val metrics = context.resources.displayMetrics
        val hasValidContainer = containerRect.width() > 80 && containerRect.height() > 80
        val left = if (hasValidContainer) containerRect.left.toFloat() else 0f
        val top = if (hasValidContainer) containerRect.top.toFloat() else 0f
        val width = if (hasValidContainer) containerRect.width().toFloat() else metrics.widthPixels.toFloat()
        val height = if (hasValidContainer) containerRect.height().toFloat() else metrics.heightPixels.toFloat()

        val cx = left + width * 0.5f
        val cy = top + height * 0.5f
        val (sx, sy, ex, ey) = when (direction) {
            ScrollDirection.DOWN -> listOf(cx, top + height * 0.78f, cx, top + height * 0.24f)
            ScrollDirection.UP -> listOf(cx, top + height * 0.24f, cx, top + height * 0.78f)
            ScrollDirection.RIGHT -> listOf(left + width * 0.82f, cy, left + width * 0.18f, cy)
            ScrollDirection.LEFT -> listOf(left + width * 0.18f, cy, left + width * 0.82f, cy)
        }
        val gestured = service.dispatchSwipe(sx, sy, ex, ey)
        if (!gestured) {
            return InteractionDispatchResult(
                executed = false,
                detail = "Scroll ${direction.apiValue} failed.",
                errorCode = "SCROLL_FAILED"
            )
        }
        delay(180)
        if (scrollableNode != null && beforeSample != null) {
            runCatching { scrollableNode.refresh() }
            val afterSwipeSample = sampleChildSignature(scrollableNode)
            if (afterSwipeSample == beforeSample) {
                return InteractionDispatchResult(
                    executed = true,
                    detail = "Swiped ${direction.apiValue}; scrollable container bounds appear unchanged (possible boundary)."
                )
            }
        }
        return InteractionDispatchResult(
            executed = true,
            detail = "Swiped ${direction.apiValue} inside viewport."
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
        block: suspend (AccessibilityNodeInfo?, ObservedUiNode, GroundingMatchExplanation) -> InteractionDispatchResult
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
                inspector.inspect(
                    root = root,
                    activePackageName = root.packageName?.toString(),
                    windowTitle = null,
                    screenWidthPx = cachedObservation.screenWidthPx,
                    screenHeightPx = cachedObservation.screenHeightPx,
                    isKeyboardVisible = service.isSoftKeyboardVisible()
                )
            }

            when (val resolution = UiElementGroundingEngine.resolve(obs, spec, preferEditable)) {
                is GroundingOutcome.StaleSnapshot -> {
                    if (attempt == 0) {
                        delay(140)
                        continue
                    }
                    return InteractionDispatchResult(
                        executed = false,
                        detail = resolution.reason,
                        errorCode = "STALE_SNAPSHOT",
                        staleNodeRefreshed = refreshed
                    )
                }
                is GroundingOutcome.NotFound -> {
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
                is GroundingOutcome.LowConfidence -> {
                    if (attempt == 0) {
                        delay(160)
                        continue
                    }
                    return InteractionDispatchResult(
                        executed = false,
                        detail = resolution.reason,
                        errorCode = "LOW_CONFIDENCE_MATCH",
                        staleNodeRefreshed = refreshed,
                        explanation = resolution.explanation,
                        ambiguousCandidates = resolution.candidates
                    )
                }
                is GroundingOutcome.Ambiguous -> {
                    if (attempt == 0) {
                        delay(160)
                        continue
                    }
                    return InteractionDispatchResult(
                        executed = false,
                        detail = resolution.reason,
                        errorCode = "AMBIGUOUS_TARGET",
                        staleNodeRefreshed = refreshed,
                        explanation = resolution.explanation,
                        ambiguousCandidates = resolution.candidates
                    )
                }
                is GroundingOutcome.Resolved -> {
                    val matchedDto = resolution.node
                    val liveNode = if (matchedDto.source == UiNodeSource.OCR_FUSED) {
                        null
                    } else {
                        findLiveNodeMatching(root, matchedDto)
                    }
                    if (matchedDto.source == UiNodeSource.ACCESSIBILITY && liveNode == null) {
                        if (attempt == 0) {
                            delay(150)
                            continue
                        }
                        return InteractionDispatchResult(
                            executed = false,
                            detail = "Matched ${describeNode(matchedDto)} in snapshot, but live accessibility node became stale.",
                            resolvedTargetLabel = describeNode(matchedDto),
                            errorCode = "STALE_NODE",
                            staleNodeRefreshed = refreshed,
                            explanation = resolution.explanation
                        )
                    }
                    val dispatch = block(liveNode, matchedDto, resolution.explanation)
                    if (dispatch.executed || attempt == 1 ||
                        dispatch.errorCode in setOf("ELEMENT_OBSTRUCTED", "ELEMENT_DISABLED")
                    ) {
                        return dispatch.copy(staleNodeRefreshed = refreshed || dispatch.staleNodeRefreshed)
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
        while (queue.isNotEmpty() && visited < 240) {
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

    private fun findBestScrollableNode(
        root: AccessibilityNodeInfo,
        direction: ScrollDirection,
        targetViewId: String?
    ): AccessibilityNodeInfo? {
        val scrollables = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        val queue = ArrayDeque<Pair<Int, AccessibilityNodeInfo>>()
        queue.add(0 to root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 200) {
            val (depth, cur) = queue.removeFirst()
            visited++
            val vid = runCatching { cur.viewIdResourceName?.substringAfterLast('/') }.getOrNull()
            if (cur.isScrollable) {
                if (!targetViewId.isNullOrBlank() && vid == targetViewId) return cur
                scrollables.add(depth to cur)
            }
            val count = runCatching { cur.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add((depth + 1) to it) }
            }
        }
        if (scrollables.isEmpty()) return null
        val wantHorizontal = direction == ScrollDirection.LEFT || direction == ScrollDirection.RIGHT
        val directional = scrollables.filter { (_, node) ->
            val cls = node.className?.toString()?.lowercase().orEmpty()
            val rect = Rect().also { runCatching { node.getBoundsInScreen(it) } }
            if (wantHorizontal) {
                cls.contains("horizontal") || cls.contains("viewpager") || cls.contains("tab") || rect.width() > rect.height()
            } else {
                !cls.contains("horizontal") && !cls.contains("viewpager")
            }
        }
        // Prefer deepest visible nested scrollable inside active container/sheet over outer shell
        val pool = directional.ifEmpty { scrollables }
        return pool.maxByOrNull { it.first }?.second
    }

    private fun sampleChildSignature(container: AccessibilityNodeInfo): String {
        val count = runCatching { container.childCount }.getOrDefault(0)
        return buildString {
            append(count).append(':')
            for (i in 0 until minOf(count, 4)) {
                val child = runCatching { container.getChild(i) }.getOrNull() ?: continue
                val rect = Rect().also { runCatching { child.getBoundsInScreen(it) } }
                append(child.text?.toString()?.take(16).orEmpty()).append('@').append(rect.top).append(',')
            }
        }
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

    open suspend fun doubleTapElement(spec: TargetResolutionSpec, observation: ScreenObservation): InteractionDispatchResult =
        backend.doubleTapElement(spec, observation)

    open suspend fun longPressElement(spec: TargetResolutionSpec, observation: ScreenObservation): InteractionDispatchResult =
        backend.longPressElement(spec, observation)

    open suspend fun tapCoordinates(x: Float, y: Float): InteractionDispatchResult =
        backend.tapCoordinates(x, y)

    open suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L
    ): InteractionDispatchResult = backend.swipe(startX, startY, endX, endY, durationMs)

    open suspend fun dragAndDrop(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 750L
    ): InteractionDispatchResult = backend.dragAndDrop(startX, startY, endX, endY, durationMs)

    open suspend fun typeText(spec: TargetResolutionSpec?, text: String, observation: ScreenObservation): InteractionDispatchResult =
        backend.typeText(spec, text, observation)

    open suspend fun editTextField(
        spec: TargetResolutionSpec?,
        mode: TextEditMode,
        text: String?,
        observation: ScreenObservation
    ): InteractionDispatchResult = backend.editTextField(spec, mode, text, observation)

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
            return when (val g = UiElementGroundingEngine.resolve(observation, spec, preferEditable)) {
                is GroundingOutcome.Resolved -> TargetResolutionOutcome.Resolved(g.node)
                is GroundingOutcome.Ambiguous -> TargetResolutionOutcome.Ambiguous(g.candidates, g.reason)
                is GroundingOutcome.LowConfidence -> TargetResolutionOutcome.Ambiguous(g.candidates, g.reason)
                is GroundingOutcome.StaleSnapshot -> TargetResolutionOutcome.NotFound(g.reason)
                is GroundingOutcome.NotFound -> TargetResolutionOutcome.NotFound(g.reason)
            }
        }
    }
}
