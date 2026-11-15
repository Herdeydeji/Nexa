package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.ExecutionPlan
import com.example.domain.model.ExecutionStepState
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ResumableTaskSnapshot
import com.example.domain.model.StepKind
import com.example.domain.model.TaskSummary
import com.example.domain.model.TaskVerificationSummary
import com.example.domain.model.ToolCall
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isSamplePreview: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastMessagePreview: String,
    val preferredProviderId: String? = null,
    val preferredModelId: String? = null
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAtMillis: Long,
    val executionPlanJson: String?,
    val taskSummaryJson: String?,
    val attachmentsJson: String?,
    val isPreviewMessage: Boolean,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val errorMessage: String? = null,
    val providerLabel: String? = null,
    val modelLabel: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val verificationJson: String? = null
)

@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val prompt: String,
    val status: AgentTaskStatus,
    val isSampleData: Boolean,
    val providerLabel: String,
    val modelLabel: String,
    val targetApp: String?,
    val requiredCapabilitiesJson: String,
    val failureReason: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val summaryMessage: String? = null,
    val verificationJson: String? = null,
    val resumableSnapshotJson: String? = null
)

@Entity(
    tableName = "task_steps",
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId")]
)
data class TaskStepEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val stepOrder: Int,
    val title: String,
    val detail: String,
    val state: ExecutionStepState,
    val toolName: String?,
    val timestampLabel: String?,
    val kind: StepKind = StepKind.EXECUTED
)

class NexaTypeConverters {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val planAdapter = moshi.adapter(ExecutionPlan::class.java)
    private val summaryAdapter = moshi.adapter(TaskSummary::class.java)
    private val verificationAdapter = moshi.adapter(TaskVerificationSummary::class.java)
    private val resumableSnapshotAdapter = moshi.adapter(ResumableTaskSnapshot::class.java)
    private val stringListAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )
    private val toolCallListAdapter = moshi.adapter<List<ToolCall>>(
        Types.newParameterizedType(List::class.java, ToolCall::class.java)
    )

    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole =
        runCatching { MessageRole.valueOf(value) }.getOrDefault(MessageRole.USER)

    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus =
        runCatching { MessageStatus.valueOf(value) }.getOrDefault(MessageStatus.COMPLETE)

    @TypeConverter
    fun fromTaskStatus(status: AgentTaskStatus): String = status.name

    @TypeConverter
    fun toTaskStatus(value: String): AgentTaskStatus =
        runCatching { AgentTaskStatus.valueOf(value) }.getOrDefault(AgentTaskStatus.FAILED)

    @TypeConverter
    fun fromStepState(state: ExecutionStepState): String = state.name

    @TypeConverter
    fun toStepState(value: String): ExecutionStepState =
        runCatching { ExecutionStepState.valueOf(value) }.getOrDefault(ExecutionStepState.FAILED)

    @TypeConverter
    fun fromStepKind(kind: StepKind): String = kind.name

    @TypeConverter
    fun toStepKind(value: String): StepKind =
        runCatching { StepKind.valueOf(value) }.getOrDefault(StepKind.EXECUTED)

    fun encodeExecutionPlan(plan: ExecutionPlan?): String? = plan?.let { planAdapter.toJson(it) }
    fun decodeExecutionPlan(json: String?): ExecutionPlan? =
        json?.let { runCatching { planAdapter.fromJson(it) }.getOrNull() }

    fun encodeTaskSummary(summary: TaskSummary?): String? = summary?.let { summaryAdapter.toJson(it) }
    fun decodeTaskSummary(json: String?): TaskSummary? =
        json?.let { runCatching { summaryAdapter.fromJson(it) }.getOrNull() }

    fun encodeVerificationSummary(summary: TaskVerificationSummary?): String? =
        summary?.let { verificationAdapter.toJson(it) }

    fun decodeVerificationSummary(json: String?): TaskVerificationSummary? =
        json?.let { runCatching { verificationAdapter.fromJson(it) }.getOrNull() }

    fun encodeResumableSnapshot(snapshot: ResumableTaskSnapshot?): String? =
        snapshot?.let { resumableSnapshotAdapter.toJson(it) }

    fun decodeResumableSnapshot(json: String?): ResumableTaskSnapshot? =
        json?.let { runCatching { resumableSnapshotAdapter.fromJson(it) }.getOrNull() }

    fun encodeToolCalls(calls: List<ToolCall>): String? =
        if (calls.isEmpty()) null else toolCallListAdapter.toJson(calls)

    fun decodeToolCalls(json: String?): List<ToolCall> =
        json?.let { runCatching { toolCallListAdapter.fromJson(it) }.getOrNull() } ?: emptyList()

    fun encodeStringList(list: List<String>): String = stringListAdapter.toJson(list)
    fun decodeStringList(json: String?): List<String> =
        json?.let { runCatching { stringListAdapter.fromJson(it) }.getOrNull() } ?: emptyList()
}
