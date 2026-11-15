package com.example.domain.model

enum class CapabilityCategory(val label: String) {
    AUTOMATION("Agent & UI Runtime"),
    CONNECTIVITY("System & Radios"),
    COMMUNICATION("Personal & Comms"),
    MEDIA_SENSORS("Media & Context")
}

enum class CapabilityStatus(val label: String) {
    AVAILABLE("Available"),
    PERMISSION_REQUIRED("Permission required"),
    RESTRICTED("Restricted"),
    UNAVAILABLE("Unavailable"),
    NOT_CONFIGURED("Not configured")
}

enum class DeviceCapabilityType(
    val id: String,
    val title: String,
    val description: String,
    val category: CapabilityCategory
) {
    APP_LAUNCHING(
        id = "app_launching",
        title = "App launching",
        description = "Resolve and launch installed target applications via explicit intents.",
        category = CapabilityCategory.AUTOMATION
    ),
    SCREEN_OBSERVATION(
        id = "screen_observation",
        title = "Screen observation",
        description = "Inspect active window hierarchy and UI tree nodes during supervised runs.",
        category = CapabilityCategory.AUTOMATION
    ),
    ACCESSIBILITY_INTERACTION(
        id = "accessibility_interaction",
        title = "Accessibility interaction",
        description = "Perform tap, scroll, and text input gestures through Nexa Accessibility Service.",
        category = CapabilityCategory.AUTOMATION
    ),
    BROWSER_INTERACTION(
        id = "browser_interaction",
        title = "Browser interaction",
        description = "Open URLs and hand off structured web searches to the default browser.",
        category = CapabilityCategory.AUTOMATION
    ),
    WIFI(
        id = "wifi",
        title = "Wi-Fi",
        description = "Inspect network reachability and open wireless settings panels.",
        category = CapabilityCategory.CONNECTIVITY
    ),
    BLUETOOTH(
        id = "bluetooth",
        title = "Bluetooth",
        description = "Check Bluetooth adapter availability and paired device settings.",
        category = CapabilityCategory.CONNECTIVITY
    ),
    BRIGHTNESS(
        id = "brightness",
        title = "Brightness",
        description = "Adjust display brightness via WRITE_SETTINGS system approval.",
        category = CapabilityCategory.CONNECTIVITY
    ),
    CONTACTS(
        id = "contacts",
        title = "Contacts",
        description = "Look up contact entries when explicitly authorized by the user.",
        category = CapabilityCategory.COMMUNICATION
    ),
    CALLS(
        id = "calls",
        title = "Calls",
        description = "Prepare dial intents or initiate calls with explicit confirmation.",
        category = CapabilityCategory.COMMUNICATION
    ),
    SMS(
        id = "sms",
        title = "SMS",
        description = "Draft messages into the default SMS app or send when authorized.",
        category = CapabilityCategory.COMMUNICATION
    ),
    FILES_AND_MEDIA(
        id = "files_and_media",
        title = "Files and media",
        description = "Select user-approved photos and documents via Photo Picker and SAF.",
        category = CapabilityCategory.MEDIA_SENSORS
    ),
    NOTIFICATIONS(
        id = "notifications",
        title = "Notifications",
        description = "Post task completion alerts and approval prompts.",
        category = CapabilityCategory.MEDIA_SENSORS
    ),
    SCREENSHOTS(
        id = "screenshots",
        title = "Screenshots",
        description = "Capture user-approved visual context via MediaProjection in Phase 2.",
        category = CapabilityCategory.MEDIA_SENSORS
    ),
    LOCATION(
        id = "location",
        title = "Location",
        description = "Provide foreground location context for location-aware tasks.",
        category = CapabilityCategory.MEDIA_SENSORS
    )
}

data class DeviceCapability(
    val type: DeviceCapabilityType,
    val status: CapabilityStatus,
    val statusReason: String,
    val userEnabled: Boolean = false
)
