package com.example.domain.agent

import com.example.android.runtime.AndroidActionRuntime
import com.example.domain.model.AppLookupOutcome
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ComputerUseActionOutcome
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.ScrollDirection
import com.example.domain.model.TargetResolutionSpec
import com.example.domain.model.ToolDefinition
import com.example.domain.model.ToolParameter
import com.example.domain.model.ToolParameterType
import com.example.domain.model.ToolRiskCategory
import com.example.domain.model.YouTubePlayStatus
import org.json.JSONArray
import org.json.JSONObject

object ComputerUseTools {
    fun createAll(runtime: AndroidActionRuntime): List<ExecutableTool> = listOf(
        ListInstalledAppsTool(runtime),
        FindAppTool(runtime),
        LaunchAppTool(runtime),
        OpenUrlTool(runtime),
        YouTubePlayTool(runtime),
        ObserveScreenTool(runtime),
        WaitForScreenChangeTool(runtime),
        TapElementTool(runtime),
        LongPressElementTool(runtime),
        TapCoordinatesTool(runtime),
        TypeTextTool(runtime),
        ScrollScreenTool(runtime),
        PressBackTool(runtime),
        PressHomeTool(runtime),
        PressEnterTool(runtime),
        TakeScreenshotTool(runtime)
    )
}

internal fun InstalledAppInfo.toJson(): JSONObject = JSONObject()
    .put("display_name", appName)
    .put("package_name", packageName)
    .put("launchable", isLaunchable)
    .put("appName", appName)
    .put("packageName", packageName)
    .put("isLaunchable", isLaunchable)
    .put("isSystemApp", isSystemApp)
    .apply {
        launcherActivity?.let { put("launcherActivity", it) }
    }

internal fun ComputerUseActionOutcome.toToolOutput(runtime: AndroidActionRuntime): ToolExecutionOutput {
    val json = runtime.formatOutcomeJson(this)
    return ToolExecutionOutput(
        outputJson = json,
        displaySummary = summary,
        isError = !success,
        errorMessage = if (success) null else summary
    )
}

internal fun parseTargetSpec(args: JSONObject, editableOnly: Boolean = false): TargetResolutionSpec {
    val nodeIndex = if (args.has("node_index") && !args.isNull("node_index")) {
        args.optInt("node_index", -1).takeIf { it >= 0 }
    } else null
    val viewId = args.optString("view_id").trim().takeIf { it.isNotEmpty() }
    val text = args.optString("text").trim().takeIf { it.isNotEmpty() }
    val contentDesc = args.optString("content_description").trim().takeIf { it.isNotEmpty() }
        ?: args.optString("hint_or_label").trim().takeIf { it.isNotEmpty() }
    return TargetResolutionSpec(
        nodeIndex = nodeIndex,
        viewId = viewId,
        text = text,
        contentDescription = contentDesc,
        editableOnly = editableOnly
    )
}

internal fun validateTargetSelectors(args: JSONObject): String? {
    val hasIndex = args.has("node_index") && !args.isNull("node_index")
    if (hasIndex) {
        val raw = args.opt("node_index")
        if (raw !is Number || raw.toInt() < 0) {
            return "Parameter 'node_index' must be a non-negative integer."
        }
    }
    val hasViewId = args.optString("view_id").isNotBlank()
    val hasText = args.optString("text").isNotBlank()
    val hasDesc = args.optString("content_description").isNotBlank()
    if (!hasIndex && !hasViewId && !hasText && !hasDesc) {
        return "Provide at least one target selector: 'node_index', 'view_id', 'text', or 'content_description'."
    }
    return null
}

class ListInstalledAppsTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.APP_LAUNCHING

    override val definition: ToolDefinition = ToolDefinition(
        id = "device.list_installed_apps",
        name = "list_installed_apps",
        description = "List launchable Android applications installed on this device, optionally filtered or paginated.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "Optional search keyword to filter apps by name or package identifier.",
                required = false
            ),
            ToolParameter(
                name = "include_system_apps",
                type = ToolParameterType.BOOLEAN,
                description = "Whether to include preinstalled system apps. Defaults to true.",
                required = false
            ),
            ToolParameter(
                name = "include_non_launchable",
                type = ToolParameterType.BOOLEAN,
                description = "Whether to also include visible installed packages without a launcher activity. Defaults to false.",
                required = false
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Maximum number of apps to return (1 to 150). Defaults to 50.",
                required = false
            ),
            ToolParameter(
                name = "offset",
                type = ToolParameterType.INTEGER,
                description = "Zero-based pagination offset into discovered apps. Defaults to 0.",
                required = false
            )
        ),
        riskCategory = ToolRiskCategory.READ_ONLY,
        resultSchemaDescription = "JSON object with totalDiscovered, count, offset, hasMore, nextOffset, visibilityNotice, and apps array.",
        timeoutMillis = 8_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        if (args.has("limit") && !args.isNull("limit")) {
            val raw = args.opt("limit")
            if (raw !is Number || raw.toInt() !in 1..150) {
                return "Parameter 'limit' must be an integer between 1 and 150."
            }
        }
        if (args.has("offset") && !args.isNull("offset")) {
            val raw = args.opt("offset")
            if (raw !is Number || raw.toInt() < 0) {
                return "Parameter 'offset' must be a non-negative integer."
            }
        }
        return null
    }

    override fun describeAction(args: JSONObject): String {
        val q = args.optString("query").trim()
        return if (q.isEmpty()) "List installed Android apps" else "List installed apps matching '$q'"
    }

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val query = args.optString("query").trim().takeIf { it.isNotEmpty() }
        val includeSystem = if (args.has("include_system_apps") && !args.isNull("include_system_apps")) {
            args.optBoolean("include_system_apps", true)
        } else true
        val includeNonLaunchable = if (args.has("include_non_launchable") && !args.isNull("include_non_launchable")) {
            args.optBoolean("include_non_launchable", false)
        } else false
        val limit = args.optInt("limit", 50).coerceIn(1, 150)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)

        val batch = runtime.listInstalledApps(
            query = query,
            includeSystemApps = includeSystem,
            limit = limit,
            includeNonLaunchable = includeNonLaunchable,
            offset = offset
        )
        val arr = JSONArray()
        batch.apps.forEach { arr.put(it.toJson()) }
        val json = JSONObject()
            .put("totalDiscovered", batch.totalDiscovered)
            .put("count", batch.apps.size)
            .put("offset", batch.offset)
            .put("hasMore", batch.hasMore)
            .put("nextOffset", batch.nextOffset ?: JSONObject.NULL)
            .put("visibilityNotice", batch.visibilityNotice)
            .put("apps", arr)
        return ToolExecutionOutput(
            outputJson = json,
            displaySummary = "Found ${batch.totalDiscovered} installed app(s)${if (query != null) " matching '$query'" else ""}"
        )
    }
}

class FindAppTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.APP_LAUNCHING

    override val definition: ToolDefinition = ToolDefinition(
        id = "device.find_app",
        name = "find_app",
        description = "Check whether a specific Android application is installed and launchable by display name or package identifier.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "App name (e.g. 'YouTube', 'Chrome', 'Settings') or package ID (e.g. 'com.android.chrome').",
                required = true
            )
        ),
        riskCategory = ToolRiskCategory.READ_ONLY,
        resultSchemaDescription = "JSON object with status (FOUND/AMBIGUOUS/NOT_LAUNCHABLE/NOT_FOUND), display_name, package_name, launchable, app, and candidates.",
        timeoutMillis = 6_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        val q = args.opt("query")
        if (q !is String || q.isBlank()) {
            return "Missing required non-empty string parameter 'query'."
        }
        return null
    }

    override fun describeAction(args: JSONObject): String =
        "Search installed apps for '${args.optString("query").trim()}'"

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val query = args.getString("query").trim()
        return when (val outcome = runtime.findApp(query)) {
            is AppLookupOutcome.Found -> {
                val altArr = JSONArray().apply {
                    outcome.alternatives.forEach { put(it.toJson()) }
                }
                val json = JSONObject()
                    .put("status", "FOUND")
                    .put("found", true)
                    .put("display_name", outcome.app.appName)
                    .put("package_name", outcome.app.packageName)
                    .put("launchable", outcome.app.isLaunchable)
                    .put("app", outcome.app.toJson())
                    .put("alternatives", altArr)
                ToolExecutionOutput(
                    outputJson = json,
                    displaySummary = "Found ${outcome.app.appName} (${outcome.app.packageName})"
                )
            }
            is AppLookupOutcome.Ambiguous -> {
                val candArr = JSONArray().apply {
                    outcome.candidates.forEach { put(it.toJson()) }
                }
                val json = JSONObject()
                    .put("status", "AMBIGUOUS")
                    .put("found", true)
                    .put("launchable", true)
                    .put("query", outcome.query)
                    .put("requires_user_action", true)
                    .put("recovery_action", "SELECT_AMBIGUOUS_APP")
                    .put("candidates", candArr)
                    .put("reason", outcome.reason)
                ToolExecutionOutput(
                    outputJson = json,
                    displaySummary = outcome.reason,
                    isError = true,
                    errorMessage = outcome.reason
                )
            }
            is AppLookupOutcome.NotLaunchable -> {
                val json = JSONObject()
                    .put("status", "NOT_LAUNCHABLE")
                    .put("found", true)
                    .put("launchable", false)
                    .put("display_name", outcome.appName)
                    .put("package_name", outcome.packageName)
                    .put("appName", outcome.appName)
                    .put("packageName", outcome.packageName)
                    .put("reason", outcome.reason)
                ToolExecutionOutput(
                    outputJson = json,
                    displaySummary = outcome.reason,
                    isError = true,
                    errorMessage = outcome.reason
                )
            }
            is AppLookupOutcome.NotFound -> {
                val json = JSONObject()
                    .put("status", "NOT_FOUND")
                    .put("found", false)
                    .put("launchable", false)
                    .put("query", outcome.query)
                    .put("visibilityRestricted", outcome.visibilityRestricted)
                    .put("reason", outcome.reason)
                ToolExecutionOutput(
                    outputJson = json,
                    displaySummary = outcome.reason,
                    isError = true,
                    errorMessage = outcome.reason
                )
            }
        }
    }
}

class LaunchAppTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.APP_LAUNCHING

    override val definition: ToolDefinition = ToolDefinition(
        id = "device.launch_app",
        name = "launch_app",
        description = "Launch an installed Android application using its launcher intent and verify actual foreground package or detect blockers such as AppLock.",
        parameters = listOf(
            ToolParameter(
                name = "app",
                type = ToolParameterType.STRING,
                description = "Application display name or package name to open (e.g. 'YouTube', 'Chrome', 'com.android.settings').",
                required = true
            )
        ),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        resultSchemaDescription = "JSON object with status (FOREGROUND_VERIFIED/LAUNCH_DISPATCHED/BLOCKED_BY_APPLOCK/BLOCKED_BY_DIALOG/APP_LOADING/NOT_FOUND/NOT_LAUNCHABLE/LAUNCH_FAILED), target_package, foreground_package, requires_user_action, recovery_action, and message.",
        timeoutMillis = 10_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        val raw = args.opt("app")
        if (raw !is String || raw.isBlank()) {
            return "Missing required non-empty string parameter 'app'."
        }
        return null
    }

    override fun describeAction(args: JSONObject): String =
        "Launch app '${args.optString("app").trim()}'"

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val appQuery = args.getString("app").trim()
        val outcome = runtime.launchApp(appQuery)
        val json = runtime.formatLaunchOutcomeJson(outcome)
        return ToolExecutionOutput(
            outputJson = json,
            displaySummary = outcome.message,
            isError = !outcome.success,
            errorMessage = if (outcome.success) null else outcome.message
        )
    }
}

class OpenUrlTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.APP_LAUNCHING

    override val definition: ToolDefinition = ToolDefinition(
        id = "device.open_url",
        name = "open_url",
        description = "Open an http or https URL directly via Android ACTION_VIEW and verify the foreground window.",
        parameters = listOf(
            ToolParameter(
                name = "url",
                type = ToolParameterType.STRING,
                description = "Complete http:// or https:// URL to open.",
                required = true
            )
        ),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        resultSchemaDescription = "Action outcome JSON with opened URL, afterPackage, screenChanged, and updated screen observation.",
        timeoutMillis = 10_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        val raw = args.opt("url")
        if (raw !is String || raw.isBlank()) {
            return "Missing required non-empty string parameter 'url'."
        }
        val clean = raw.trim().lowercase()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return "Parameter 'url' must begin with 'http://' or 'https://'."
        }
        return null
    }

    override fun describeAction(args: JSONObject): String =
        "Open URL '${args.optString("url").trim().take(60)}'"

    override suspend fun execute(args: JSONObject): ToolExecutionOutput =
        runtime.openUrl(args.getString("url").trim()).toToolOutput(runtime)
}

class YouTubePlayTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.APP_LAUNCHING

    override val definition: ToolDefinition = ToolDefinition(
        id = "media.play_on_youtube",
        name = "play_on_youtube",
        description = "Open YouTube search directly for the user's query, select a grounded matching video result when Accessibility is active, and verify real playback signals.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "Video, artist, or topic search query to play on YouTube.",
                required = true
            )
        ),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        resultSchemaDescription = "JSON object with status (PLAYBACK_STARTED/PLAYBACK_UNVERIFIED/VIDEO_SELECTED/RESULTS_OBSERVED/SEARCH_OPENED/BLOCKED_BY_APPLOCK/FAILED), query, searchUrl, target_package, foreground_package, selected_title, playback_signal, and message.",
        timeoutMillis = 12_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        val q = args.opt("query")
        if (q !is String || q.isBlank()) {
            return "Missing required non-empty string parameter 'query'."
        }
        return null
    }

    override fun describeAction(args: JSONObject): String =
        "Play on YouTube: '${args.optString("query").trim()}'"

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val query = args.getString("query").trim()
        val outcome = runtime.playOnYouTube(query)
        val json = JSONObject()
            .put("status", outcome.status.name)
            .put("query", outcome.query)
            .put("searchUrl", outcome.searchUrl)
            .put("message", outcome.message)
        outcome.targetPackage?.let {
            json.put("target_package", it)
            json.put("packageName", it)
        }
        outcome.observedForegroundPackage?.let {
            json.put("foreground_package", it)
            json.put("observedForegroundPackage", it)
        }
        outcome.selectedTitle?.let { json.put("selected_title", it) }
        outcome.selectedChannel?.let { json.put("selected_channel", it) }
        outcome.playbackSignal?.let { json.put("playback_signal", it) }
        if (outcome.status == YouTubePlayStatus.BLOCKED_BY_APPLOCK) {
            val lo = outcome.launchOutcome
            json.put("errorCode", "BLOCKED_BY_APPLOCK")
            json.put("requires_user_action", true)
            json.put("recovery_action", "WAIT_FOR_USER_UNLOCK")
            json.put("appName", lo?.appName ?: "YouTube")
            json.put("user_title", lo?.userTitle ?: "Unlock YouTube")
            json.put("user_message", lo?.userMessage ?: outcome.message)
            json.put("reason", lo?.blockerReason ?: "The target application is protected by Transsion AppLock.")
        }
        outcome.postObservation?.let { post ->
            json.put("screen", runtime.screenObserver.toCompactJson(post))
        }
        val isErr = outcome.status == YouTubePlayStatus.FAILED || outcome.status == YouTubePlayStatus.BLOCKED_BY_APPLOCK
        return ToolExecutionOutput(
            outputJson = json,
            displaySummary = outcome.message,
            isError = isErr,
            errorMessage = if (isErr) outcome.message else null
        )
    }
}

class ObserveScreenTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.SCREEN_OBSERVATION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.observe_screen",
        name = "observe_screen",
        description = "Observe the active Android window and return a compact structured representation of visible text, roles, bounds, and clickable/editable/scrollable UI elements with sensitive fields redacted.",
        parameters = emptyList(),
        riskCategory = ToolRiskCategory.READ_ONLY,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "JSON object with activePackageName, windowTitle, isInteractive, isDeviceLocked, isRestrictedWindow, nodeCount, truncated, and nodes array.",
        timeoutMillis = 8_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? = null

    override fun describeAction(args: JSONObject): String = "Observe active Android screen"

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val obs = runtime.observeScreen()
        val json = runtime.screenObserver.toCompactJson(obs)
        val hasErr = obs.observationError != null
        val summary = obs.observationError ?: "Observed ${obs.nodes.size} UI element(s) in ${obs.activePackageName ?: "active window"}"
        return ToolExecutionOutput(
            outputJson = json,
            displaySummary = summary,
            isError = hasErr && obs.nodes.isEmpty(),
            errorMessage = if (hasErr && obs.nodes.isEmpty()) obs.observationError else null
        )
    }
}

class WaitForScreenChangeTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.SCREEN_OBSERVATION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.wait_for_screen_change",
        name = "wait_for_screen_change",
        description = "Wait for the active screen window or UI hierarchy to change (e.g. after launching an app or tapping a navigation item) and return the new screen observation.",
        parameters = listOf(
            ToolParameter(
                name = "timeout_ms",
                type = ToolParameterType.INTEGER,
                description = "Maximum wait time in milliseconds (300 to 10000). Defaults to 3000.",
                required = false
            ),
            ToolParameter(
                name = "expected_package",
                type = ToolParameterType.STRING,
                description = "Optional package name expected to appear in foreground.",
                required = false
            )
        ),
        riskCategory = ToolRiskCategory.READ_ONLY,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "Action outcome JSON including screenChanged and latest post-observation screen.",
        timeoutMillis = 12_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        if (args.has("timeout_ms") && !args.isNull("timeout_ms")) {
            val raw = args.opt("timeout_ms")
            if (raw !is Number || raw.toInt() !in 300..10_000) {
                return "Parameter 'timeout_ms' must be an integer between 300 and 10000."
            }
        }
        return null
    }

    override fun describeAction(args: JSONObject): String {
        val pkg = args.optString("expected_package").trim()
        return if (pkg.isEmpty()) "Wait for screen update" else "Wait for foreground window '$pkg'"
    }

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val timeoutMs = args.optLong("timeout_ms", 3_000L).coerceIn(300L, 10_000L)
        val expectedPkg = args.optString("expected_package").trim().takeIf { it.isNotEmpty() }
        return runtime.waitForScreenChange(timeoutMs, expectedPkg).toToolOutput(runtime)
    }
}

class TapElementTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.ACCESSIBILITY_INTERACTION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.tap_element",
        name = "tap_element",
        description = "Tap a visible UI element on screen resolved by node_index, view_id, text, or content_description, then verify resulting UI state.",
        parameters = listOf(
            ToolParameter("node_index", ToolParameterType.INTEGER, "Node index from latest observe_screen output.", required = false),
            ToolParameter("view_id", ToolParameterType.STRING, "Accessibility view resource ID or suffix.", required = false),
            ToolParameter("text", ToolParameterType.STRING, "Visible text displayed on or inside the element.", required = false),
            ToolParameter("content_description", ToolParameterType.STRING, "Accessibility content description of the element.", required = false)
        ),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "Action outcome JSON with success, verificationDetail, screenChanged, and updated screen observation.",
        timeoutMillis = 10_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? = validateTargetSelectors(args)

    override fun describeAction(args: JSONObject): String {
        val spec = parseTargetSpec(args)
        val label = spec.text ?: spec.contentDescription ?: spec.viewId ?: "node #${spec.nodeIndex}"
        return "Tap UI element '$label'"
    }

    override suspend fun execute(args: JSONObject): ToolExecutionOutput =
        runtime.tapElement(parseTargetSpec(args)).toToolOutput(runtime)
}

class LongPressElementTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.ACCESSIBILITY_INTERACTION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.long_press_element",
        name = "long_press_element",
        description = "Perform a long-press action on a visible UI element resolved by node_index, view_id, text, or content_description.",
        parameters = listOf(
            ToolParameter("node_index", ToolParameterType.INTEGER, "Node index from latest observe_screen output.", required = false),
            ToolParameter("view_id", ToolParameterType.STRING, "Accessibility view resource ID or suffix.", required = false),
            ToolParameter("text", ToolParameterType.STRING, "Visible text of the target element.", required = false),
            ToolParameter("content_description", ToolParameterType.STRING, "Accessibility content description of the element.", required = false)
        ),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "Action outcome JSON with verificationDetail and updated screen observation.",
        timeoutMillis = 10_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? = validateTargetSelectors(args)

    override fun describeAction(args: JSONObject): String {
        val spec = parseTargetSpec(args)
        val label = spec.text ?: spec.contentDescription ?: spec.viewId ?: "node #${spec.nodeIndex}"
        return "Long-press UI element '$label'"
    }

    override suspend fun execute(args: JSONObject): ToolExecutionOutput =
        runtime.longPressElement(parseTargetSpec(args)).toToolOutput(runtime)
}

class TapCoordinatesTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.ACCESSIBILITY_INTERACTION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.tap_coordinates",
        name = "tap_coordinates",
        description = "Dispatch a screen tap gesture at explicit pixel coordinates (x, y) when node clicking is unavailable, then verify resulting UI state.",
        parameters = listOf(
            ToolParameter("x", ToolParameterType.NUMBER, "Horizontal pixel coordinate (non-negative).", required = true),
            ToolParameter("y", ToolParameterType.NUMBER, "Vertical pixel coordinate (non-negative).", required = true)
        ),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "Action outcome JSON with verificationDetail and updated screen observation.",
        timeoutMillis = 10_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        val x = args.opt("x") as? Number ?: return "Missing numeric parameter 'x'."
        val y = args.opt("y") as? Number ?: return "Missing numeric parameter 'y'."
        if (x.toFloat() < 0f || y.toFloat() < 0f) {
            return "Coordinates 'x' and 'y' must be non-negative."
        }
        return null
    }

    override fun describeAction(args: JSONObject): String =
        "Tap screen coordinates (${args.opt("x")}, ${args.opt("y")})"

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val x = (args.get("x") as Number).toFloat()
        val y = (args.get("y") as Number).toFloat()
        return runtime.tapCoordinates(x, y).toToolOutput(runtime)
    }
}

class TypeTextTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.ACCESSIBILITY_INTERACTION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.type_text",
        name = "type_text",
        description = "Enter text into a targeted or currently focused editable field. Refuses password/PIN/OTP/CVV fields automatically.",
        parameters = listOf(
            ToolParameter("text", ToolParameterType.STRING, "Text to enter into the input field.", required = true),
            ToolParameter("node_index", ToolParameterType.INTEGER, "Optional node index of the editable field.", required = false),
            ToolParameter("view_id", ToolParameterType.STRING, "Optional view ID of the editable field.", required = false),
            ToolParameter("hint_or_label", ToolParameterType.STRING, "Optional hint or label text to locate the editable field.", required = false)
        ),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "Action outcome JSON confirming text entry and updated screen observation.",
        timeoutMillis = 10_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        val text = args.opt("text")
        if (text !is String) {
            return "Missing required string parameter 'text'."
        }
        return null
    }

    override fun describeAction(args: JSONObject): String {
        val hint = args.optString("hint_or_label").ifBlank { args.optString("view_id") }
        val combined = "$hint ${args.optString("text")}".lowercase()
        val sensitive = listOf("password", "passcode", "pin", "otp", "cvv", "secret").any { combined.contains(it) }
        val preview = if (sensitive) "[REDACTED]" else "'${args.optString("text").take(32)}'"
        return if (hint.isNotBlank()) "Type $preview into '$hint'" else "Type $preview into focused field"
    }

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val text = args.getString("text")
        val hasTarget = (args.has("node_index") && !args.isNull("node_index")) ||
            args.optString("view_id").isNotBlank() ||
            args.optString("hint_or_label").isNotBlank()
        val spec = if (hasTarget) parseTargetSpec(args, editableOnly = true) else null
        return runtime.typeText(spec, text).toToolOutput(runtime)
    }
}

class ScrollScreenTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.ACCESSIBILITY_INTERACTION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.scroll_screen",
        name = "scroll_screen",
        description = "Scroll the active scrollable container or screen viewport in a direction ('down', 'up', 'left', or 'right').",
        parameters = listOf(
            ToolParameter(
                name = "direction",
                type = ToolParameterType.STRING,
                description = "Scroll direction: 'down', 'up', 'left', or 'right'.",
                required = true,
                enumValues = listOf("down", "up", "left", "right")
            ),
            ToolParameter(
                name = "target_view_id",
                type = ToolParameterType.STRING,
                description = "Optional view ID of a specific scrollable container.",
                required = false
            )
        ),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "Action outcome JSON with screenChanged and updated screen observation.",
        timeoutMillis = 10_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        ScrollDirection.parse(args.optString("direction"))
            ?: return "Parameter 'direction' must be one of: down, up, left, right."
        return null
    }

    override fun describeAction(args: JSONObject): String =
        "Scroll screen ${args.optString("direction").lowercase()}"

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val dir = ScrollDirection.parse(args.getString("direction")) ?: ScrollDirection.DOWN
        val viewId = args.optString("target_view_id").trim().takeIf { it.isNotEmpty() }
        return runtime.scrollScreen(dir, viewId).toToolOutput(runtime)
    }
}

class PressBackTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.ACCESSIBILITY_INTERACTION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.press_back",
        name = "press_back",
        description = "Perform the global Android Back navigation action.",
        parameters = emptyList(),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "Action outcome JSON with verificationDetail and updated screen observation.",
        timeoutMillis = 8_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? = null
    override fun describeAction(args: JSONObject): String = "Press Android Back"
    override suspend fun execute(args: JSONObject): ToolExecutionOutput =
        runtime.pressBack().toToolOutput(runtime)
}

class PressHomeTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.ACCESSIBILITY_INTERACTION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.press_home",
        name = "press_home",
        description = "Perform the global Android Home navigation action to return to the launcher.",
        parameters = emptyList(),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "Action outcome JSON with verificationDetail and updated screen observation.",
        timeoutMillis = 8_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? = null
    override fun describeAction(args: JSONObject): String = "Press Android Home"
    override suspend fun execute(args: JSONObject): ToolExecutionOutput =
        runtime.pressHome().toToolOutput(runtime)
}

class PressEnterTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.ACCESSIBILITY_INTERACTION

    override val definition: ToolDefinition = ToolDefinition(
        id = "ui.press_enter",
        name = "press_enter",
        description = "Trigger IME Enter / Search / Submit on the currently focused input field, or click a visible submit/search button if IME action is unsupported.",
        parameters = emptyList(),
        riskCategory = ToolRiskCategory.REVERSIBLE,
        requiredPermissions = listOf("ACCESSIBILITY_SERVICE"),
        resultSchemaDescription = "Action outcome JSON with verificationDetail and updated screen observation.",
        timeoutMillis = 8_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? = null
    override fun describeAction(args: JSONObject): String = "Press Enter / Submit"
    override suspend fun execute(args: JSONObject): ToolExecutionOutput =
        runtime.pressEnter().toToolOutput(runtime)
}

class TakeScreenshotTool(
    private val runtime: AndroidActionRuntime
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType = DeviceCapabilityType.SCREENSHOTS

    override val definition: ToolDefinition = ToolDefinition(
        id = "media.take_screenshot",
        name = "take_screenshot",
        description = "Capture a real screenshot of the current Android display using an active MediaProjection session, save it as PNG in app cache, and attach visual context when supported.",
        parameters = listOf(
            ToolParameter(
                name = "include_visual_context",
                type = ToolParameterType.BOOLEAN,
                description = "Whether to include image visual context for vision-capable models. Defaults to true.",
                required = false
            )
        ),
        riskCategory = ToolRiskCategory.READ_ONLY,
        requiredPermissions = listOf("MEDIA_PROJECTION"),
        resultSchemaDescription = "JSON object with success, filePath, width, height, mimeType, timestampMillis, requiresConsent, and message.",
        timeoutMillis = 12_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? = null

    override fun describeAction(args: JSONObject): String = "Capture device screenshot via MediaProjection"

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val includeVisual = if (args.has("include_visual_context") && !args.isNull("include_visual_context")) {
            args.optBoolean("include_visual_context", true)
        } else true
        val outcome = runtime.takeScreenshot(includeBase64 = includeVisual)
        val json = JSONObject()
            .put("success", outcome.success)
            .put("requiresConsent", outcome.requiresConsent)
            .put("mimeType", outcome.mimeType)
            .put("width", outcome.width)
            .put("height", outcome.height)
            .put("timestampMillis", outcome.timestampMillis)
            .put("message", outcome.message)
        outcome.filePath?.let { json.put("filePath", it) }
        outcome.errorCode?.let { json.put("errorCode", it) }

        val visualAttachment = if (outcome.success && !outcome.base64Data.isNullOrBlank()) {
            json.put("visualContextAvailable", true)
            AttachmentPayload(
                id = "screenshot_${outcome.timestampMillis}",
                displayName = outcome.filePath?.substringAfterLast('/') ?: "screenshot.png",
                mimeType = "image/jpeg",
                sourceUri = outcome.filePath ?: "",
                imageBase64 = outcome.base64Data,
                byteSize = outcome.base64Data.length.toLong(),
                isSupported = true
            )
        } else {
            json.put("visualContextAvailable", false)
            null
        }

        return ToolExecutionOutput(
            outputJson = json,
            displaySummary = outcome.message,
            isError = !outcome.success,
            errorMessage = if (outcome.success) null else outcome.message,
            visualAttachment = visualAttachment
        )
    }
}
