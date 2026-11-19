package com.example.android.accessibility

import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.example.domain.model.ObservedUiNode
import com.example.domain.model.ScreenObservation
import com.example.domain.model.UiBounds
import com.example.domain.model.WindowDialogType
import java.security.MessageDigest

interface RawNodeAdapter {
    val viewIdResourceName: String?
    val className: String?
    val text: String?
    val contentDescription: String?
    val hintText: String?
    val bounds: UiBounds
    val isClickable: Boolean
    val isEditable: Boolean
    val isScrollable: Boolean
    val isCheckable: Boolean
    val isChecked: Boolean
    val isFocused: Boolean
    val isEnabled: Boolean
    val isVisibleToUser: Boolean
    val isPassword: Boolean
    val inputType: Int
    val isLongClickable: Boolean get() = false
    val isSelected: Boolean get() = false
    val supportedActionNames: List<String> get() = emptyList()
    val childCount: Int
    fun childAt(index: Int): RawNodeAdapter?
}

class AndroidAccessibilityNodeAdapter(
    private val node: AccessibilityNodeInfo
) : RawNodeAdapter {
    override val viewIdResourceName: String?
        get() = runCatching { node.viewIdResourceName }.getOrNull()
    override val className: String?
        get() = runCatching { node.className?.toString() }.getOrNull()
    override val text: String?
        get() = runCatching { node.text?.toString() }.getOrNull()
    override val contentDescription: String?
        get() = runCatching { node.contentDescription?.toString() }.getOrNull()
    override val hintText: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { node.hintText?.toString() }.getOrNull()
        } else {
            null
        }
    override val bounds: UiBounds
        get() {
            val rect = Rect()
            runCatching { node.getBoundsInScreen(rect) }
            return UiBounds(rect.left, rect.top, rect.right, rect.bottom)
        }
    override val isClickable: Boolean
        get() = runCatching { node.isClickable }.getOrDefault(false)
    override val isEditable: Boolean
        get() = runCatching { node.isEditable }.getOrDefault(false)
    override val isScrollable: Boolean
        get() = runCatching { node.isScrollable }.getOrDefault(false)
    override val isCheckable: Boolean
        get() = runCatching { node.isCheckable }.getOrDefault(false)
    override val isChecked: Boolean
        get() = runCatching { node.isChecked }.getOrDefault(false)
    override val isFocused: Boolean
        get() = runCatching { node.isFocused }.getOrDefault(false)
    override val isEnabled: Boolean
        get() = runCatching { node.isEnabled }.getOrDefault(true)
    override val isVisibleToUser: Boolean
        get() = runCatching { node.isVisibleToUser }.getOrDefault(true)
    override val isPassword: Boolean
        get() = runCatching { node.isPassword }.getOrDefault(false)
    override val inputType: Int
        get() = runCatching { node.inputType }.getOrDefault(InputType.TYPE_NULL)
    override val isLongClickable: Boolean
        get() = runCatching { node.isLongClickable }.getOrDefault(false)
    override val isSelected: Boolean
        get() = runCatching { node.isSelected }.getOrDefault(false)
    override val supportedActionNames: List<String>
        get() = runCatching {
            node.actionList.mapNotNull { action ->
                when (action.id) {
                    AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
                    AccessibilityNodeInfo.ACTION_LONG_CLICK -> "LONG_CLICK"
                    AccessibilityNodeInfo.ACTION_SET_TEXT -> "SET_TEXT"
                    AccessibilityNodeInfo.ACTION_PASTE -> "PASTE"
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "SCROLL_FORWARD"
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "SCROLL_BACKWARD"
                    AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
                    AccessibilityNodeInfo.ACTION_SELECT -> "SELECT"
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    ) "IME_ENTER" else null
                }
            }.distinct()
        }.getOrDefault(emptyList())
    override val childCount: Int
        get() = runCatching { node.childCount }.getOrDefault(0)

    override fun childAt(index: Int): RawNodeAdapter? {
        val child = runCatching { node.getChild(index) }.getOrNull() ?: return null
        return AndroidAccessibilityNodeAdapter(child)
    }
}

class AccessibilityNodeInspector(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxVisitedNodes: Int = DEFAULT_MAX_VISITED_NODES,
    private val maxEmittedNodes: Int = DEFAULT_MAX_EMITTED_NODES,
    private val maxTextLength: Int = DEFAULT_MAX_TEXT_LENGTH
) {

    fun inspect(
        root: AccessibilityNodeInfo?,
        activePackageName: String?,
        windowTitle: String?,
        timestampMillis: Long = System.currentTimeMillis(),
        screenWidthPx: Int = 1080,
        screenHeightPx: Int = 2400,
        isKeyboardVisible: Boolean = false
    ): ScreenObservation {
        if (root == null) {
            return ScreenObservation(
                timestampMillis = timestampMillis,
                activePackageName = activePackageName,
                windowTitle = windowTitle,
                isInteractive = false,
                isRestrictedWindow = true,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                isKeyboardVisible = isKeyboardVisible,
                hasSparseHierarchy = true,
                hierarchyCompleteness = 0f,
                screenSummary = "Restricted or empty accessibility window for ${activePackageName ?: "foreground"}",
                observationError = "Active window root is unavailable or protected by the system (for example a secure/FLAG_SECURE window or transition)."
            )
        }
        return inspectAdapter(
            root = AndroidAccessibilityNodeAdapter(root),
            activePackageName = activePackageName ?: runCatching { root.packageName?.toString() }.getOrNull(),
            windowTitle = windowTitle,
            timestampMillis = timestampMillis,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            isKeyboardVisible = isKeyboardVisible
        )
    }

    fun inspectAdapter(
        root: RawNodeAdapter,
        activePackageName: String?,
        windowTitle: String?,
        timestampMillis: Long = System.currentTimeMillis(),
        screenWidthPx: Int = 1080,
        screenHeightPx: Int = 2400,
        isKeyboardVisible: Boolean = false
    ): ScreenObservation {
        val emitted = mutableListOf<ObservedUiNode>()
        val childMap = mutableMapOf<Int, MutableList<Int>>()
        val seenSignatures = HashSet<String>()
        var visitedCount = 0
        var truncated = false
        var hasProgressBar = false
        var webViewCount = 0
        var unlabeledClickableCount = 0

        fun traverse(node: RawNodeAdapter, depth: Int, parentEmittedIdx: Int?) {
            if (visitedCount >= maxVisitedNodes || emitted.size >= maxEmittedNodes || depth > maxDepth) {
                truncated = true
                return
            }
            visitedCount++
            if (!node.isVisibleToUser) return

            val bounds = node.bounds
            val rawViewId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            val rawText = node.text?.trim()?.takeIf { it.isNotEmpty() }
            val rawDesc = node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
            val rawHint = node.hintText?.trim()?.takeIf { it.isNotEmpty() }

            val lowerClass = node.className?.lowercase().orEmpty()
            if (lowerClass.contains("progressbar")) {
                hasProgressBar = true
            }
            if (lowerClass.contains("webview")) {
                webViewCount++
            }

            val sensitive = isSensitiveNode(node, rawViewId, rawHint, rawDesc)
            val safeText = when {
                sensitive && rawText != null -> REDACTED_VALUE
                rawText != null -> truncateText(rawText)
                else -> null
            }
            val safeDesc = when {
                sensitive && node.isPassword && rawDesc != null -> REDACTED_VALUE
                rawDesc != null -> truncateText(rawDesc)
                else -> null
            }
            val safeHint = rawHint?.let { truncateText(it) }
            val role = classifyRole(node, rawViewId, safeHint, safeDesc)

            val hasMeaningfulContent = safeText != null ||
                safeDesc != null ||
                node.isClickable ||
                node.isEditable ||
                node.isScrollable ||
                node.isCheckable ||
                (rawViewId != null && bounds.width > 0 && bounds.height > 0)

            var currentEmittedIdx = parentEmittedIdx
            if (hasMeaningfulContent && bounds.width >= 0 && bounds.height >= 0) {
                val dedupeKey = "${role}|${safeText.orEmpty()}|${safeDesc.orEmpty()}|${rawViewId.orEmpty()}|${bounds.toCompactString()}"
                if (seenSignatures.add(dedupeKey)) {
                    val idx = emitted.size
                    if (node.isClickable && safeText == null && safeDesc == null && rawHint == null) {
                        unlabeledClickableCount++
                    }
                    emitted.add(
                        ObservedUiNode(
                            index = idx,
                            viewId = rawViewId,
                            className = node.className?.substringAfterLast('.'),
                            role = role,
                            text = safeText,
                            contentDescription = safeDesc,
                            hintText = safeHint,
                            bounds = bounds,
                            clickable = node.isClickable,
                            editable = node.isEditable,
                            scrollable = node.isScrollable,
                            checkable = node.isCheckable,
                            checked = node.isChecked,
                            focused = node.isFocused,
                            enabled = node.isEnabled,
                            isPassword = node.isPassword,
                            isSensitiveRedacted = sensitive,
                            depth = depth,
                            parentIndex = parentEmittedIdx,
                            longClickable = node.isLongClickable,
                            selected = node.isSelected,
                            supportedActions = node.supportedActionNames
                        )
                    )
                    if (parentEmittedIdx != null) {
                        childMap.getOrPut(parentEmittedIdx) { mutableListOf() }.add(idx)
                    }
                    currentEmittedIdx = idx
                }
            }

            val count = node.childCount
            for (i in 0 until count) {
                if (visitedCount >= maxVisitedNodes || emitted.size >= maxEmittedNodes) {
                    truncated = true
                    break
                }
                val child = node.childAt(i) ?: continue
                traverse(child, depth + 1, currentEmittedIdx)
            }
        }

        traverse(root, 0, null)

        val finalNodes = emitted.map { node ->
            val children = childMap[node.index]
            if (children.isNullOrEmpty()) node else node.copy(childIndices = children.toList())
        }

        val dialogType = classifyDialogType(activePackageName, finalNodes)
        val loadingTextHit = finalNodes.any { n ->
            val txt = (n.text ?: n.contentDescription).orEmpty().lowercase()
            txt == "loading..." || txt == "loading" || txt.contains("buffering") || txt.contains("please wait")
        }
        val isLoadingLikely = hasProgressBar || (loadingTextHit && finalNodes.size <= 5)

        val labeledNodes = finalNodes.count {
            !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() || !it.hintText.isNullOrBlank()
        }
        val completeness = when {
            finalNodes.isEmpty() -> 0f
            webViewCount > 0 && labeledNodes <= 3 -> 0.25f
            finalNodes.size <= 3 && labeledNodes <= 1 -> 0.3f
            unlabeledClickableCount > labeledNodes && unlabeledClickableCount >= 3 -> 0.45f
            else -> (labeledNodes.toFloat() / finalNodes.size.toFloat()).coerceIn(0.35f, 1.0f)
        }
        val hasSparseHierarchy = completeness < 0.5f || finalNodes.size <= 2 || (webViewCount > 0 && labeledNodes <= 3)

        val keyboardInferred = isKeyboardVisible ||
            (finalNodes.any { it.focused && it.editable } && activePackageName?.contains("inputmethod", ignoreCase = true) == true)

        val summary = buildScreenSummary(
            pkg = activePackageName,
            nodes = finalNodes,
            dialogType = dialogType,
            isLoading = isLoadingLikely,
            keyboardVisible = keyboardInferred,
            sparse = hasSparseHierarchy
        )

        val signature = computeSignature(activePackageName, windowTitle, finalNodes)
        return ScreenObservation(
            timestampMillis = timestampMillis,
            activePackageName = activePackageName,
            windowTitle = windowTitle,
            windowType = if (dialogType == WindowDialogType.SYSTEM_PERMISSION) "system" else "application",
            isInteractive = true,
            isDeviceLocked = false,
            isRestrictedWindow = false,
            nodes = finalNodes,
            totalNodesVisited = visitedCount,
            truncatedByLimit = truncated,
            observationError = null,
            hierarchySignature = signature,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            isKeyboardVisible = keyboardInferred,
            dialogType = dialogType,
            isLoadingLikely = isLoadingLikely,
            hierarchyCompleteness = completeness,
            hasSparseHierarchy = hasSparseHierarchy,
            screenSummary = summary
        )
    }

    internal fun classifyDialogType(pkg: String?, nodes: List<ObservedUiNode>): WindowDialogType {
        val cleanPkg = pkg?.trim()?.lowercase().orEmpty()
        if (cleanPkg == "com.transsion.applock") return WindowDialogType.APPLOCK
        if (cleanPkg in setOf(
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
                "com.google.android.packageinstaller"
            )
        ) {
            return WindowDialogType.SYSTEM_PERMISSION
        }
        val hasPermHint = nodes.any { n ->
            val vid = n.viewId?.lowercase().orEmpty()
            vid.contains("permission_") || vid.contains("grant_dialog")
        }
        if (hasPermHint) return WindowDialogType.SYSTEM_PERMISSION

        val hasDialogHint = nodes.any { n ->
            val vid = n.viewId?.lowercase().orEmpty()
            val cls = n.className?.lowercase().orEmpty()
            vid.contains("alerttitle") || vid == "button1" || vid == "button2" || cls.contains("dialog")
        }
        return if (hasDialogHint) WindowDialogType.APP_DIALOG else WindowDialogType.NONE
    }

    private fun buildScreenSummary(
        pkg: String?,
        nodes: List<ObservedUiNode>,
        dialogType: WindowDialogType,
        isLoading: Boolean,
        keyboardVisible: Boolean,
        sparse: Boolean
    ): String = buildString {
        append(pkg ?: "unknown_app")
        append(": ${nodes.size} nodes")
        val editCount = nodes.count { it.editable }
        val clickCount = nodes.count { it.clickable }
        val scrollCount = nodes.count { it.scrollable }
        if (clickCount > 0) append(", $clickCount clickable")
        if (editCount > 0) append(", $editCount editable")
        if (scrollCount > 0) append(", $scrollCount scrollable")
        if (keyboardVisible) append(", IME keyboard open")
        if (dialogType != WindowDialogType.NONE) append(", dialog=${dialogType.name}")
        if (isLoading) append(", loading=true")
        if (sparse) append(", sparse_tree=true")
    }

    internal fun isSensitiveNode(
        node: RawNodeAdapter,
        viewId: String?,
        hint: String?,
        contentDesc: String?
    ): Boolean {
        if (node.isPassword) return true
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                )
        ) {
            return true
        }
        if (inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return true
        }
        if (!node.isEditable) return false
        val meta = listOfNotNull(viewId, hint, contentDesc).joinToString(" ").lowercase()
        return SENSITIVE_KEYWORDS.any { meta.contains(it) }
    }

    internal fun classifyRole(
        node: RawNodeAdapter,
        viewId: String?,
        hint: String?,
        desc: String?
    ): String {
        val cls = node.className?.lowercase().orEmpty()
        val meta = listOfNotNull(viewId, hint, desc).joinToString(" ").lowercase()
        return when {
            node.isPassword -> "PasswordField"
            node.isEditable || cls.contains("edittext") || cls.contains("autocompletetextview") -> {
                if (meta.contains("search") || meta.contains("url") || meta.contains("address")) {
                    "SearchOrAddressField"
                } else {
                    "TextField"
                }
            }
            cls.contains("switch") -> "Switch"
            cls.contains("checkbox") -> "CheckBox"
            cls.contains("radiobutton") -> "RadioButton"
            cls.contains("button") || cls.contains("imagebutton") -> "Button"
            node.isScrollable || cls.contains("recyclerview") || cls.contains("scrollview") || cls.contains("listview") || cls.contains("viewpager") -> "ScrollContainer"
            cls.contains("tab") -> "Tab"
            cls.contains("webview") -> "WebView"
            cls.contains("imageview") -> if (node.isClickable) "ImageButton" else "Image"
            cls.contains("textview") -> if (node.isClickable) "ClickableText" else "Text"
            node.isClickable -> "ClickableContainer"
            else -> "Container"
        }
    }

    private fun truncateText(raw: String): String {
        val singleLine = raw.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= maxTextLength) {
            singleLine
        } else {
            singleLine.take(maxTextLength - 1) + "…"
        }
    }

    private fun computeSignature(
        pkg: String?,
        title: String?,
        nodes: List<ObservedUiNode>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val summary = buildString {
            append(pkg.orEmpty()).append('|').append(title.orEmpty()).append('|')
            nodes.take(40).forEach { n ->
                append(n.role).append(':')
                append(n.text.orEmpty()).append(':')
                append(n.viewId.orEmpty()).append(':')
                append(n.bounds.toCompactString()).append(';')
            }
        }
        return digest.digest(summary.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 16
        const val DEFAULT_MAX_VISITED_NODES = 200
        const val DEFAULT_MAX_EMITTED_NODES = 75
        const val DEFAULT_MAX_TEXT_LENGTH = 120
        const val REDACTED_VALUE = "[REDACTED_SENSITIVE]"

        private val SENSITIVE_KEYWORDS = listOf(
            "password", "passcode", "pin", "cvv", "cvc", "otp",
            "verification code", "secret", "passphrase", "card number", "security code"
        )
    }
}
