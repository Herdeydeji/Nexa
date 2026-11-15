package com.example.domain.agent

import com.example.android.runtime.AndroidActionRuntime
import com.example.domain.model.AppSettings
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.DeviceCapability
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolCallResult
import com.example.domain.model.ToolExecutionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class RuntimeAction(
    val toolId: String,
    val arguments: Map<String, String>
)

sealed interface RuntimeResult {
    data class NotImplementedInPhase1(val reason: String) : RuntimeResult
    data class BlockedBySafetyPolicy(val reason: String) : RuntimeResult
    data class Failed(val error: String) : RuntimeResult
}

interface ExecutionRuntime {
    val isAutomationActive: Boolean
    suspend fun execute(action: RuntimeAction): RuntimeResult
}

class Phase1StubExecutionRuntime : ExecutionRuntime {
    override val isAutomationActive: Boolean = false

    override suspend fun execute(action: RuntimeAction): RuntimeResult {
        return RuntimeResult.NotImplementedInPhase1(
            "Execution runtime is offline in Phase 1. Tool '${action.toolId}' was not executed on the device."
        )
    }
}

class ToolExecutionRuntime(
    private val toolRegistry: ToolRegistry,
    private val timeoutDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val androidActionRuntime: AndroidActionRuntime? = null,
    private val liveCapabilitiesProvider: () -> List<DeviceCapability> = { emptyList() }
) : ExecutionRuntime {
    private val transientVisualAttachments = ConcurrentHashMap<String, AttachmentPayload>()

    override val isAutomationActive: Boolean
        get() = androidActionRuntime?.isAutomationActive() == true

    fun consumeVisualAttachment(callId: String): AttachmentPayload? =
        transientVisualAttachments.remove(callId)

    override suspend fun execute(action: RuntimeAction): RuntimeResult {
        val tool = toolRegistry.findExecutableTool(action.toolId)
            ?: return RuntimeResult.Failed("Unknown tool '${action.toolId}'.")
        val argsJson = JSONObject(action.arguments)
        val validationError = tool.validateArguments(argsJson)
        if (validationError != null) {
            return RuntimeResult.Failed(validationError)
        }
        return try {
            tool.execute(argsJson)
            RuntimeResult.NotImplementedInPhase1("Use executeTool(ToolCall) for structured Phase 3 tool output.")
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            RuntimeResult.Failed(t.message ?: "Tool execution failed.")
        }
    }

    suspend fun executeToolCall(
        call: ToolCall,
        settings: AppSettings,
        timeoutMillis: Long,
        maxResultChars: Int
    ): ToolCallResult {
        currentCoroutineContext().ensureActive()
        val startMs = System.currentTimeMillis()
        val tool = toolRegistry.findExecutableTool(call.toolName)
            ?: return ToolCallResult(
                callId = call.id,
                toolName = call.toolName,
                status = ToolExecutionStatus.FAILED,
                outputJson = errorJson("Unknown tool '${call.toolName}'."),
                displaySummary = "Rejected: unknown tool '${call.toolName}'",
                errorMessage = "Tool '${call.toolName}' is not registered in Nexa.",
                durationMillis = System.currentTimeMillis() - startMs
            )

        if (!tool.definition.isImplemented) {
            return ToolCallResult(
                callId = call.id,
                toolName = call.toolName,
                status = ToolExecutionStatus.FAILED,
                outputJson = errorJson("Tool '${call.toolName}' is not implemented in this build."),
                displaySummary = "Unavailable: '${call.toolName}' is not enabled",
                errorMessage = "Tool '${call.toolName}' is not implemented in this build.",
                durationMillis = System.currentTimeMillis() - startMs
            )
        }

        val liveCaps = liveCapabilitiesProvider()
        if (liveCaps.isNotEmpty()) {
            val availability = toolRegistry.getAvailability(call.toolName, liveCaps)
            if (availability != null && !availability.isAvailable) {
                return ToolCallResult(
                    callId = call.id,
                    toolName = call.toolName,
                    status = ToolExecutionStatus.FAILED,
                    outputJson = errorJson(availability.availabilityReason),
                    displaySummary = "${availability.availabilityStatus.label}: ${availability.availabilityReason}",
                    errorMessage = availability.availabilityReason,
                    durationMillis = System.currentTimeMillis() - startMs
                )
            }
        }

        val parsedArgs = parseArgsObject(call.argumentsJson)
            ?: return ToolCallResult(
                callId = call.id,
                toolName = call.toolName,
                status = ToolExecutionStatus.FAILED,
                outputJson = errorJson("Malformed JSON arguments for tool '${call.toolName}'."),
                displaySummary = "Invalid JSON arguments",
                errorMessage = "Malformed JSON arguments for tool '${call.toolName}'.",
                durationMillis = System.currentTimeMillis() - startMs
            )

        val validationError = tool.validateArguments(parsedArgs)
        if (validationError != null) {
            return ToolCallResult(
                callId = call.id,
                toolName = call.toolName,
                status = ToolExecutionStatus.FAILED,
                outputJson = errorJson(validationError),
                displaySummary = "Invalid arguments: $validationError",
                errorMessage = validationError,
                durationMillis = System.currentTimeMillis() - startMs
            )
        }

        val effectiveTimeout = minOf(timeoutMillis, tool.definition.timeoutMillis).coerceAtLeast(500L)
        return try {
            val timedOut = AtomicBoolean(false)
            var output: ToolExecutionOutput? = null
            coroutineScope {
                val worker = launch {
                    output = tool.execute(parsedArgs)
                }
                val watchdog = launch(timeoutDispatcher) {
                    delay(effectiveTimeout)
                    if (output == null) {
                        timedOut.set(true)
                        worker.cancel(CancellationException("Tool '${call.toolName}' timed out"))
                    }
                }
                worker.join()
                watchdog.cancel()
            }
            val finalOutput = output
            if (timedOut.get() || finalOutput == null) {
                return ToolCallResult(
                    callId = call.id,
                    toolName = call.toolName,
                    status = ToolExecutionStatus.TIMED_OUT,
                    outputJson = errorJson("Tool '${call.toolName}' timed out after ${effectiveTimeout}ms."),
                    displaySummary = "Timed out after ${effectiveTimeout}ms",
                    errorMessage = "Tool '${call.toolName}' timed out after ${effectiveTimeout}ms.",
                    durationMillis = System.currentTimeMillis() - startMs
                )
            }
            val rawJson = finalOutput.outputJson.toString()
            val (boundedJson, truncated) = boundOutput(rawJson, maxResultChars)
            val visualAttachment = finalOutput.visualAttachment
            if (visualAttachment != null && !finalOutput.isError) {
                transientVisualAttachments[call.id] = visualAttachment
            }
            val summary = if (settings.redactSensitiveStepsInLogs &&
                tool.definition.riskCategory == com.example.domain.model.ToolRiskCategory.SENSITIVE
            ) {
                "Executed sensitive tool '${tool.definition.name}' (details redacted)"
            } else {
                finalOutput.displaySummary
            }
            val finalStatus = if (finalOutput.isError) {
                ToolExecutionStatus.FAILED
            } else {
                ToolExecutionStatus.SUCCEEDED
            }
            ToolCallResult(
                callId = call.id,
                toolName = call.toolName,
                status = finalStatus,
                outputJson = boundedJson,
                displaySummary = summary,
                errorMessage = if (finalOutput.isError) (finalOutput.errorMessage ?: summary) else null,
                durationMillis = System.currentTimeMillis() - startMs,
                wasTruncated = truncated
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val msg = t.message?.takeIf { it.isNotBlank() } ?: "Tool execution error."
            ToolCallResult(
                callId = call.id,
                toolName = call.toolName,
                status = ToolExecutionStatus.FAILED,
                outputJson = errorJson(msg),
                displaySummary = "Error: $msg",
                errorMessage = msg,
                durationMillis = System.currentTimeMillis() - startMs
            )
        }
    }

    fun parseArgsObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JSONObject()
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private fun boundOutput(rawJson: String, maxChars: Int): Pair<String, Boolean> {
        if (rawJson.length <= maxChars) return rawJson to false
        val truncatedSlice = rawJson.take((maxChars - 120).coerceAtLeast(100))
        val wrapped = JSONObject()
            .put("truncated", true)
            .put("originalLengthChars", rawJson.length)
            .put("partialOutput", truncatedSlice)
            .toString()
        return wrapped to true
    }

    private fun errorJson(message: String): String =
        JSONObject().put("error", message).toString()
}
