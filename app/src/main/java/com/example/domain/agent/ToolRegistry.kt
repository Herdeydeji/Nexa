package com.example.domain.agent

import android.content.Context
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.ToolDefinition

enum class ToolRiskLevel(val label: String) {
    LOW("Read / Safe"),
    MEDIUM("UI Action"),
    HIGH("Sensitive / External")
}

data class AgentTool(
    val id: String,
    val name: String,
    val description: String,
    val requiredCapability: DeviceCapabilityType,
    val riskLevel: ToolRiskLevel,
    val isPhase1Implemented: Boolean = false
)

interface ToolRegistry {
    fun getAllTools(): List<AgentTool>
    fun findTool(id: String): AgentTool?
    fun toolsForCapability(type: DeviceCapabilityType): List<AgentTool>

    fun getExecutableTools(): List<ExecutableTool> = emptyList()
    fun findExecutableTool(nameOrId: String): ExecutableTool? = null
    fun getAvailableToolDefinitions(capabilities: List<DeviceCapability> = emptyList()): List<ToolDefinition> =
        getExecutableTools().map { it.definition }
    fun registerTool(tool: ExecutableTool) {}
}

class DefaultToolRegistry(
    context: Context? = null,
    initialExecutableTools: List<ExecutableTool> = listOf(
        CalculatorTool(),
        DateTimeTool(),
        DeviceInfoTool(context)
    )
) : ToolRegistry {

    private val executableTools = initialExecutableTools.toMutableList()

    private val legacyContractTools: List<AgentTool> = listOf(
        AgentTool(
            id = "utility.calculator",
            name = "Calculator",
            description = "Evaluate deterministic arithmetic expressions, decimals, parentheses, and percentages.",
            requiredCapability = DeviceCapabilityType.FILES_AND_MEDIA,
            riskLevel = ToolRiskLevel.LOW,
            isPhase1Implemented = true
        ),
        AgentTool(
            id = "utility.datetime",
            name = "Date & Time",
            description = "Read current Android device date, time, day of week, and timezone.",
            requiredCapability = DeviceCapabilityType.FILES_AND_MEDIA,
            riskLevel = ToolRiskLevel.LOW,
            isPhase1Implemented = true
        ),
        AgentTool(
            id = "device.info",
            name = "Device Information",
            description = "Inspect non-sensitive OS version, device model, storage capacity, and battery status.",
            requiredCapability = DeviceCapabilityType.FILES_AND_MEDIA,
            riskLevel = ToolRiskLevel.LOW,
            isPhase1Implemented = true
        ),
        AgentTool(
            id = "device.launch_app",
            name = "Launch Application",
            description = "Resolve package name or intent and open an installed Android app (planned future capability).",
            requiredCapability = DeviceCapabilityType.APP_LAUNCHING,
            riskLevel = ToolRiskLevel.MEDIUM
        ),
        AgentTool(
            id = "ui.observe_screen",
            name = "Inspect Screen Tree",
            description = "Capture structured UI accessibility nodes on the active screen (planned future capability).",
            requiredCapability = DeviceCapabilityType.SCREEN_OBSERVATION,
            riskLevel = ToolRiskLevel.MEDIUM
        ),
        AgentTool(
            id = "ui.perform_gesture",
            name = "Tap / Scroll / Type",
            description = "Execute UI interactions on targeted screen elements via Accessibility (planned future capability).",
            requiredCapability = DeviceCapabilityType.ACCESSIBILITY_INTERACTION,
            riskLevel = ToolRiskLevel.MEDIUM
        ),
        AgentTool(
            id = "browser.open_url",
            name = "Open Web Link",
            description = "Launch a sanitized URL or search query in the user's browser (planned future capability).",
            requiredCapability = DeviceCapabilityType.BROWSER_INTERACTION,
            riskLevel = ToolRiskLevel.LOW
        ),
        AgentTool(
            id = "comms.draft_sms",
            name = "Draft SMS",
            description = "Compose an SMS message with explicit human confirmation before dispatch (planned future capability).",
            requiredCapability = DeviceCapabilityType.SMS,
            riskLevel = ToolRiskLevel.HIGH
        ),
        AgentTool(
            id = "comms.prepare_call",
            name = "Prepare Phone Call",
            description = "Open the dialer or request approval before placing a call (planned future capability).",
            requiredCapability = DeviceCapabilityType.CALLS,
            riskLevel = ToolRiskLevel.HIGH
        ),
        AgentTool(
            id = "system.toggle_settings",
            name = "System Panel Action",
            description = "Inspect Wi-Fi, Bluetooth, or display brightness controls (planned future capability).",
            requiredCapability = DeviceCapabilityType.BRIGHTNESS,
            riskLevel = ToolRiskLevel.MEDIUM
        ),
        AgentTool(
            id = "media.pick_attachment",
            name = "Attach User Media",
            description = "Attach user-selected images or files via Android Photo Picker / SAF.",
            requiredCapability = DeviceCapabilityType.FILES_AND_MEDIA,
            riskLevel = ToolRiskLevel.LOW,
            isPhase1Implemented = true
        )
    )

    override fun getAllTools(): List<AgentTool> = legacyContractTools

    override fun findTool(id: String): AgentTool? = legacyContractTools.firstOrNull { it.id == id }

    override fun toolsForCapability(type: DeviceCapabilityType): List<AgentTool> =
        legacyContractTools.filter { it.requiredCapability == type }

    override fun getExecutableTools(): List<ExecutableTool> = executableTools.toList()

    override fun findExecutableTool(nameOrId: String): ExecutableTool? =
        executableTools.firstOrNull {
            it.definition.name.equals(nameOrId, ignoreCase = false) ||
                it.definition.id.equals(nameOrId, ignoreCase = false)
        }

    override fun getAvailableToolDefinitions(capabilities: List<DeviceCapability>): List<ToolDefinition> {
        val statusByType = capabilities.associateBy { it.type }
        return executableTools
            .filter { tool ->
                if (!tool.definition.isImplemented) return@filter false
                val reqCap = tool.requiredCapability ?: return@filter true
                val inspected = statusByType[reqCap] ?: return@filter true
                inspected.status == CapabilityStatus.AVAILABLE
            }
            .map { it.definition }
    }

    override fun registerTool(tool: ExecutableTool) {
        executableTools.removeAll { it.definition.name == tool.definition.name || it.definition.id == tool.definition.id }
        executableTools.add(tool)
    }
}
