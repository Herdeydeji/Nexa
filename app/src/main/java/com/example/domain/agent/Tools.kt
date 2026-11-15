package com.example.domain.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.ToolDefinition
import com.example.domain.model.ToolParameter
import com.example.domain.model.ToolParameterType
import com.example.domain.model.ToolRiskCategory
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class ToolExecutionOutput(
    val outputJson: JSONObject,
    val displaySummary: String,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val visualAttachment: AttachmentPayload? = null
)

interface ExecutableTool {
    val definition: ToolDefinition
    val requiredCapability: DeviceCapabilityType?

    fun validateArguments(args: JSONObject): String?

    fun describeAction(args: JSONObject): String

    suspend fun execute(args: JSONObject): ToolExecutionOutput
}

class CalculatorTool : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType? = null

    override val definition: ToolDefinition = ToolDefinition(
        id = "utility.calculator",
        name = "calculator",
        description = "Evaluate an arithmetic expression deterministically using +, -, *, /, parentheses, decimals, and percentages (e.g. '14500 + 2750 - 500' or '20% of 16750').",
        parameters = listOf(
            ToolParameter(
                name = "expression",
                type = ToolParameterType.STRING,
                description = "The arithmetic expression to evaluate, such as '14500 + 2750 - 500' or '16750 * 20%'.",
                required = true
            )
        ),
        riskCategory = ToolRiskCategory.READ_ONLY,
        resultSchemaDescription = "JSON object with expression, normalizedExpression, result (string), numericResult (number).",
        timeoutMillis = 5_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        if (!args.has("expression") || args.isNull("expression")) {
            return "Missing required parameter 'expression'."
        }
        val raw = args.opt("expression")
        if (raw !is String || raw.isBlank()) {
            return "Parameter 'expression' must be a non-empty string."
        }
        return null
    }

    override fun describeAction(args: JSONObject): String {
        val expr = args.optString("expression").ifBlank { "(empty)" }
        return "Evaluate mathematical expression: $expr"
    }

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val rawExpr = args.getString("expression")
        val calc = CalculatorExpressionParser.evaluate(rawExpr)
        val out = JSONObject()
            .put("expression", rawExpr)
            .put("normalizedExpression", calc.normalizedExpression)
            .put("result", calc.formattedResult)
            .put("numericResult", calc.result.toDouble())
        return ToolExecutionOutput(
            outputJson = out,
            displaySummary = "${calc.normalizedExpression} = ${calc.formattedResult}"
        )
    }
}

class DateTimeTool(
    private val clockMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val defaultTimeZoneProvider: () -> TimeZone = { TimeZone.getDefault() }
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType? = null

    override val definition: ToolDefinition = ToolDefinition(
        id = "utility.datetime",
        name = "get_current_datetime",
        description = "Read the live current date, time, day of week, UTC offset, and timezone from the Android device clock.",
        parameters = listOf(
            ToolParameter(
                name = "timezone",
                type = ToolParameterType.STRING,
                description = "Optional IANA timezone ID (e.g. 'UTC', 'America/New_York', 'Africa/Lagos'). Defaults to the device timezone.",
                required = false
            )
        ),
        riskCategory = ToolRiskCategory.READ_ONLY,
        resultSchemaDescription = "JSON object with date, time24, time12, dayOfWeek, iso8601, timezoneId, timezoneName, utcOffset, epochMillis.",
        timeoutMillis = 5_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        if (args.has("timezone") && !args.isNull("timezone")) {
            val tz = args.opt("timezone")
            if (tz !is String) {
                return "Optional parameter 'timezone' must be a string."
            }
            if (tz.isNotBlank()) {
                val known = TimeZone.getAvailableIDs().toHashSet()
                if (tz.trim() !in known) {
                    return "Unknown timezone ID '${tz.trim()}'."
                }
            }
        }
        return null
    }

    override fun describeAction(args: JSONObject): String {
        val tz = args.optString("timezone").takeIf { it.isNotBlank() } ?: "device local timezone"
        return "Read current date and time ($tz)"
    }

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val requestedTz = args.optString("timezone").trim()
        val tz = if (requestedTz.isNotEmpty()) {
            TimeZone.getTimeZone(requestedTz)
        } else {
            defaultTimeZoneProvider()
        }
        val nowMillis = clockMillisProvider()
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }

        fun fmt(pattern: String): String = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = tz
        }.format(cal.time)

        val rawOffsetMillis = tz.getOffset(nowMillis)
        val sign = if (rawOffsetMillis >= 0) "+" else "-"
        val absMinutes = kotlin.math.abs(rawOffsetMillis) / 60_000
        val offsetFormatted = String.format(Locale.US, "%s%02d:%02d", sign, absMinutes / 60, absMinutes % 60)

        val dateStr = fmt("yyyy-MM-dd")
        val time24Str = fmt("HH:mm:ss")
        val time12Str = fmt("hh:mm:ss a")
        val dayStr = fmt("EEEE")
        val isoStr = "${dateStr}T${time24Str}$offsetFormatted"

        val out = JSONObject()
            .put("date", dateStr)
            .put("time24", time24Str)
            .put("time12", time12Str)
            .put("dayOfWeek", dayStr)
            .put("iso8601", isoStr)
            .put("timezoneId", tz.id)
            .put("timezoneName", tz.getDisplayName(tz.inDaylightTime(cal.time), TimeZone.LONG, Locale.US))
            .put("utcOffset", offsetFormatted)
            .put("epochMillis", nowMillis)

        return ToolExecutionOutput(
            outputJson = out,
            displaySummary = "$dayStr $dateStr $time24Str (${tz.id} $offsetFormatted)"
        )
    }
}

class DeviceInfoTool(
    private val context: Context? = null
) : ExecutableTool {
    override val requiredCapability: DeviceCapabilityType? = null

    override val definition: ToolDefinition = ToolDefinition(
        id = "device.info",
        name = "get_device_info",
        description = "Read non-sensitive Android device information: OS version, SDK level, manufacturer, model, available/total internal storage, and battery status. Omits all unique identifiers.",
        parameters = listOf(
            ToolParameter(
                name = "include_storage",
                type = ToolParameterType.BOOLEAN,
                description = "Whether to include internal storage metrics. Defaults to true.",
                required = false
            ),
            ToolParameter(
                name = "include_battery",
                type = ToolParameterType.BOOLEAN,
                description = "Whether to include battery percentage and charging state. Defaults to true.",
                required = false
            )
        ),
        riskCategory = ToolRiskCategory.READ_ONLY,
        resultSchemaDescription = "JSON object containing androidVersion, sdkInt, manufacturer, model, availableStorageBytes, totalStorageBytes, availableStorageFormatted, totalStorageFormatted, batteryPercentage, isCharging.",
        timeoutMillis = 5_000L,
        isImplemented = true
    )

    override fun validateArguments(args: JSONObject): String? {
        if (args.has("include_storage") && !args.isNull("include_storage") && args.opt("include_storage") !is Boolean) {
            return "Parameter 'include_storage' must be a boolean."
        }
        if (args.has("include_battery") && !args.isNull("include_battery") && args.opt("include_battery") !is Boolean) {
            return "Parameter 'include_battery' must be a boolean."
        }
        return null
    }

    override fun describeAction(args: JSONObject): String =
        "Read non-sensitive Android OS, storage, and battery information"

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val includeStorage = args.optBoolean("include_storage", true)
        val includeBattery = args.optBoolean("include_battery", true)

        val androidVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "unavailable"
        val sdkInt = Build.VERSION.SDK_INT
        val manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "unavailable"
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "unavailable"

        var availableBytes: Long? = null
        var totalBytes: Long? = null
        var availableFormatted: String? = null
        var totalFormatted: String? = null

        if (includeStorage) {
            runCatching {
                val dataDir = Environment.getDataDirectory()
                val statFs = StatFs(dataDir.path)
                availableBytes = statFs.availableBytes
                totalBytes = statFs.totalBytes
                availableFormatted = formatBytes(statFs.availableBytes)
                totalFormatted = formatBytes(statFs.totalBytes)
            }
        }

        var batteryPercent: Int? = null
        var isCharging: Boolean? = null
        if (includeBattery) {
            context?.let { ctx ->
                runCatching {
                    val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    if (intent != null) {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        if (level >= 0 && scale > 0) {
                            batteryPercent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
                        }
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        if (status != -1) {
                            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL
                        }
                    }
                    if (batteryPercent == null) {
                        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                        val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        if (cap != null && cap in 0..100) {
                            batteryPercent = cap
                        }
                    }
                }
            }
        }

        val out = JSONObject()
            .put("androidVersion", androidVersion)
            .put("sdkInt", sdkInt)
            .put("manufacturer", manufacturer)
            .put("model", model)
            .put("availableStorageBytes", availableBytes ?: JSONObject.NULL)
            .put("totalStorageBytes", totalBytes ?: JSONObject.NULL)
            .put("availableStorageFormatted", availableFormatted ?: "unavailable")
            .put("totalStorageFormatted", totalFormatted ?: "unavailable")
            .put("batteryPercentage", batteryPercent ?: JSONObject.NULL)
            .put("isCharging", isCharging ?: JSONObject.NULL)

        val summaryParts = buildList {
            add("Android $androidVersion (API $sdkInt)")
            add("$manufacturer $model".trim())
            if (availableFormatted != null) add("$availableFormatted free")
            if (batteryPercent != null) add("Battery $batteryPercent%")
        }

        return ToolExecutionOutput(
            outputJson = out,
            displaySummary = summaryParts.joinToString(" · ")
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "unavailable"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            String.format(Locale.US, "%.1f MB", mb)
        }
    }
}
