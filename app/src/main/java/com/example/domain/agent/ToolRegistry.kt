package com.example.domain.agent

import android.content.Context
import com.example.android.runtime.AndroidActionRuntime
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.ToolDefinition
import com.example.domain.model.ToolRiskCategory

enum class ToolRiskLevel(val label: String) {
    LOW("Read / Safe"),
    MEDIUM("UI Action"),
    HIGH("Sensitive / External")
}

enum class CapabilityRequirement(val label: String) {
    NONE("Always available"),
    APP_LAUNCHING("App discovery & launching"),
    SCREEN_OBSERVATION("Accessibility screen observation"),
    ACCESSIBILITY_INTERACTION("Accessibility UI interaction"),
    MEDIA_PROJECTION_CONSENT("MediaProjection screen capture consent");

    companion object {
        fun fromCapability(type: DeviceCapabilityType?): CapabilityRequirement = when (type) {
            null -> NONE
            DeviceCapabilityType.APP_LAUNCHING -> APP_LAUNCHING
            DeviceCapabilityType.SCREEN_OBSERVATION -> SCREEN_OBSERVATION
            DeviceCapabilityType.ACCESSIBILITY_INTERACTION,
            DeviceCapabilityType.BROWSER_INTERACTION -> ACCESSIBILITY_INTERACTION
            DeviceCapabilityType.SCREENSHOTS -> MEDIA_PROJECTION_CONSENT
            else -> NONE
        }
    }
}

enum class ToolAvailabilityStatus(val label: String) {
    AVAILABLE("Available"),
    SETUP_REQUIRED("Setup required"),
    CONSENT_REQUIRED("Consent required"),
    TEMPORARILY_UNAVAILABLE("Temporarily unavailable"),
    UNSUPPORTED("Unsupported"),
    DISABLED_BY_USER("Disabled by user")
}

enum class ToolRecoveryAction {
    NONE,
    OPEN_ACCESSIBILITY_SETTINGS,
    REQUEST_MEDIA_PROJECTION,
    OPEN_APP_SETTINGS
}

data class RegisteredToolEntry(
    val tool: ExecutableTool,
    val capabilityRequirement: CapabilityRequirement,
    val availabilityStatus: ToolAvailabilityStatus,
    val availabilityReason: String,
    val recoveryAction: ToolRecoveryAction = ToolRecoveryAction.NONE,
    val userEnabled: Boolean = true
) {
    val definition: ToolDefinition get() = tool.definition
    val isAvailable: Boolean get() = availabilityStatus == ToolAvailabilityStatus.AVAILABLE
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

    fun getAllRegisteredTools(capabilities: List<DeviceCapability> = emptyList()): List<RegisteredToolEntry> =
        getExecutableTools().map { tool ->
            RegisteredToolEntry(
                tool = tool,
                capabilityRequirement = CapabilityRequirement.fromCapability(tool.requiredCapability),
                availabilityStatus = if (tool.definition.isImplemented) {
                    ToolAvailabilityStatus.AVAILABLE
                } else {
                    ToolAvailabilityStatus.UNSUPPORTED
                },
                availabilityReason = if (tool.definition.isImplemented) "Available" else "Not implemented"
            )
        }

    fun getAvailableTools(capabilities: List<DeviceCapability> = emptyList()): List<RegisteredToolEntry> =
        getAllRegisteredTools(capabilities).filter { it.isAvailable }

    fun getUnavailableTools(capabilities: List<DeviceCapability> = emptyList()): List<RegisteredToolEntry> =
        getAllRegisteredTools(capabilities).filterNot { it.isAvailable }

    fun getAvailability(
        toolNameOrId: String,
        capabilities: List<DeviceCapability> = emptyList()
    ): RegisteredToolEntry? = getAllRegisteredTools(capabilities).firstOrNull {
        it.definition.name.equals(toolNameOrId, ignoreCase = false) ||
            it.definition.id.equals(toolNameOrId, ignoreCase = false)
    }

    fun registerTool(tool: ExecutableTool) {}
}

class DefaultToolRegistry(
    context: Context? = null,
    initialExecutableTools: List<ExecutableTool> = listOf(
        CalculatorTool(),
        DateTimeTool(),
        DeviceInfoTool(context)
    ),
    actionRuntime: AndroidActionRuntime? = null,
    private val memoryRepository: com.example.data.repository.MemoryRepository? = null,
    private val localMemoryEnabledProvider: () -> Boolean = { true }
) : ToolRegistry {

    private val executableTools = initialExecutableTools.toMutableList()

    init {
        if (actionRuntime != null) {
            ComputerUseTools.createAll(actionRuntime).forEach { registerTool(it) }
        }
        if (memoryRepository != null) {
            registerTool(
                SaveMemoryNoteTool(
                    onSaveMemory = { note, category -> memoryRepository.addMemory(note, category) },
                    isMemoryEnabled = localMemoryEnabledProvider
                )
            )
        }
    }

    private val plannedNonExecutableCatalog: List<AgentTool> = listOf(
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

    override fun getAllTools(): List<AgentTool> {
        val fromExecutable = executableTools.map { exec ->
            AgentTool(
                id = exec.definition.id,
                name = exec.definition.name,
                description = exec.definition.description,
                requiredCapability = exec.requiredCapability ?: DeviceCapabilityType.FILES_AND_MEDIA,
                riskLevel = when (exec.definition.riskCategory) {
                    ToolRiskCategory.READ_ONLY -> ToolRiskLevel.LOW
                    ToolRiskCategory.REVERSIBLE -> ToolRiskLevel.MEDIUM
                    ToolRiskCategory.SENSITIVE,
                    ToolRiskCategory.DESTRUCTIVE,
                    ToolRiskCategory.EXTERNAL_SIDE_EFFECT -> ToolRiskLevel.HIGH
                },
                isPhase1Implemented = exec.definition.isImplemented
            )
        }
        val existingIds = fromExecutable.mapTo(mutableSetOf()) { it.id }
        return fromExecutable + plannedNonExecutableCatalog.filter { it.id !in existingIds }
    }

    override fun findTool(id: String): AgentTool? = getAllTools().firstOrNull { it.id == id }

    override fun toolsForCapability(type: DeviceCapabilityType): List<AgentTool> =
        getAllTools().filter { it.requiredCapability == type }

    override fun getExecutableTools(): List<ExecutableTool> = executableTools.toList()

    override fun findExecutableTool(nameOrId: String): ExecutableTool? =
        executableTools.firstOrNull {
            it.definition.name.equals(nameOrId, ignoreCase = false) ||
                it.definition.id.equals(nameOrId, ignoreCase = false)
        }

    override fun getAllRegisteredTools(capabilities: List<DeviceCapability>): List<RegisteredToolEntry> {
        val statusByType = capabilities.associateBy { it.type }
        return executableTools.map { tool ->
            evaluateToolAvailability(tool, statusByType)
        }
    }

    override fun getAvailableToolDefinitions(capabilities: List<DeviceCapability>): List<ToolDefinition> =
        getAvailableTools(capabilities).map { it.definition }

    override fun registerTool(tool: ExecutableTool) {
        executableTools.removeAll { it.definition.name == tool.definition.name || it.definition.id == tool.definition.id }
        executableTools.add(tool)
    }

    private fun evaluateToolAvailability(
        tool: ExecutableTool,
        statusByType: Map<DeviceCapabilityType, DeviceCapability>
    ): RegisteredToolEntry {
        val requirement = CapabilityRequirement.fromCapability(tool.requiredCapability)
        if (!tool.definition.isImplemented) {
            return RegisteredToolEntry(
                tool = tool,
                capabilityRequirement = requirement,
                availabilityStatus = ToolAvailabilityStatus.UNSUPPORTED,
                availabilityReason = "Tool '${tool.definition.name}' is not implemented in this build."
            )
        }

        if (tool.definition.name == "save_memory_note" && !localMemoryEnabledProvider()) {
            return RegisteredToolEntry(
                tool = tool,
                capabilityRequirement = CapabilityRequirement.NONE,
                availabilityStatus = ToolAvailabilityStatus.DISABLED_BY_USER,
                availabilityReason = "On-device context memory is disabled in Settings > Memory Settings.",
                userEnabled = false
            )
        }

        val reqCap = tool.requiredCapability
        if (reqCap == null) {
            return RegisteredToolEntry(
                tool = tool,
                capabilityRequirement = CapabilityRequirement.NONE,
                availabilityStatus = ToolAvailabilityStatus.AVAILABLE,
                availabilityReason = "Always available (device utility)."
            )
        }

        val inspected = statusByType[reqCap]
            ?: return RegisteredToolEntry(
                tool = tool,
                capabilityRequirement = requirement,
                availabilityStatus = ToolAvailabilityStatus.AVAILABLE,
                availabilityReason = "Available (no capability restriction reported)."
            )

        if (!inspected.userEnabled) {
            return RegisteredToolEntry(
                tool = tool,
                capabilityRequirement = requirement,
                availabilityStatus = ToolAvailabilityStatus.DISABLED_BY_USER,
                availabilityReason = "${reqCap.title} is toggled off in Connections -> Capabilities.",
                userEnabled = false
            )
        }

        val recovery = when (reqCap) {
            DeviceCapabilityType.SCREEN_OBSERVATION,
            DeviceCapabilityType.ACCESSIBILITY_INTERACTION,
            DeviceCapabilityType.BROWSER_INTERACTION -> ToolRecoveryAction.OPEN_ACCESSIBILITY_SETTINGS
            DeviceCapabilityType.SCREENSHOTS -> ToolRecoveryAction.REQUEST_MEDIA_PROJECTION
            DeviceCapabilityType.NOTIFICATIONS -> ToolRecoveryAction.OPEN_APP_SETTINGS
            else -> ToolRecoveryAction.NONE
        }

        val status = when (inspected.status) {
            CapabilityStatus.AVAILABLE -> ToolAvailabilityStatus.AVAILABLE
            CapabilityStatus.PERMISSION_REQUIRED,
            CapabilityStatus.NOT_CONFIGURED -> if (reqCap == DeviceCapabilityType.SCREENSHOTS) {
                ToolAvailabilityStatus.CONSENT_REQUIRED
            } else {
                ToolAvailabilityStatus.SETUP_REQUIRED
            }
            CapabilityStatus.TEMPORARILY_UNAVAILABLE -> ToolAvailabilityStatus.TEMPORARILY_UNAVAILABLE
            CapabilityStatus.UNAVAILABLE,
            CapabilityStatus.RESTRICTED -> ToolAvailabilityStatus.UNSUPPORTED
        }

        return RegisteredToolEntry(
            tool = tool,
            capabilityRequirement = requirement,
            availabilityStatus = status,
            availabilityReason = inspected.statusReason,
            recoveryAction = if (status == ToolAvailabilityStatus.AVAILABLE) ToolRecoveryAction.NONE else recovery,
            userEnabled = inspected.userEnabled
        )
    }
}
