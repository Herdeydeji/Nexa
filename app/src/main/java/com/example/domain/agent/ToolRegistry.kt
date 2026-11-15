package com.example.domain.agent

import com.example.domain.model.DeviceCapabilityType

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
}

class DefaultToolRegistry : ToolRegistry {

    private val tools: List<AgentTool> = listOf(
        AgentTool(
            id = "device.launch_app",
            name = "Launch Application",
            description = "Resolve package name or intent and open an installed Android app.",
            requiredCapability = DeviceCapabilityType.APP_LAUNCHING,
            riskLevel = ToolRiskLevel.MEDIUM
        ),
        AgentTool(
            id = "ui.observe_screen",
            name = "Inspect Screen Tree",
            description = "Capture structured UI accessibility nodes on the active screen.",
            requiredCapability = DeviceCapabilityType.SCREEN_OBSERVATION,
            riskLevel = ToolRiskLevel.MEDIUM
        ),
        AgentTool(
            id = "ui.perform_gesture",
            name = "Tap / Scroll / Type",
            description = "Execute UI interactions on targeted screen elements via Accessibility.",
            requiredCapability = DeviceCapabilityType.ACCESSIBILITY_INTERACTION,
            riskLevel = ToolRiskLevel.MEDIUM
        ),
        AgentTool(
            id = "browser.open_url",
            name = "Open Web Link",
            description = "Launch a sanitized URL or search query in the user's browser.",
            requiredCapability = DeviceCapabilityType.BROWSER_INTERACTION,
            riskLevel = ToolRiskLevel.LOW
        ),
        AgentTool(
            id = "comms.draft_sms",
            name = "Draft SMS",
            description = "Compose an SMS message with explicit human confirmation before dispatch.",
            requiredCapability = DeviceCapabilityType.SMS,
            riskLevel = ToolRiskLevel.HIGH
        ),
        AgentTool(
            id = "comms.prepare_call",
            name = "Prepare Phone Call",
            description = "Open the dialer or request approval before placing a call.",
            requiredCapability = DeviceCapabilityType.CALLS,
            riskLevel = ToolRiskLevel.HIGH
        ),
        AgentTool(
            id = "system.toggle_settings",
            name = "System Panel Action",
            description = "Inspect Wi-Fi, Bluetooth, or display brightness controls.",
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

    override fun getAllTools(): List<AgentTool> = tools

    override fun findTool(id: String): AgentTool? = tools.firstOrNull { it.id == id }

    override fun toolsForCapability(type: DeviceCapabilityType): List<AgentTool> =
        tools.filter { it.requiredCapability == type }
}
