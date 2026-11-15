package com.example.domain.agent

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
