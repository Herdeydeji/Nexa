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
import com.example.domain.model.TaskSummary
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
    val lastMessagePreview: String
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
    val totalTokens: Int? = null
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
    val updatedAtMillis: Long
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
    val timestampLabel: String?
)

class NexaTypeConverters {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val planAdapter = moshi.adapter(ExecutionPlan::class.java)
    private val summaryAdapter = moshi.adapter(TaskSummary::class.java)
    private val stringListAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = MessageRole.valueOf(value)

    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus =
        runCatching { MessageStatus.valueOf(value) }.getOrDefault(MessageStatus.COMPLETE)

    @TypeConverter
    fun fromTaskStatus(status: AgentTaskStatus): String = status.name

    @TypeConverter
    fun toTaskStatus(value: String): AgentTaskStatus = AgentTaskStatus.valueOf(value)

    @TypeConverter
    fun fromStepState(state: ExecutionStepState): String = state.name

    @TypeConverter
    fun toStepState(value: String): ExecutionStepState = ExecutionStepState.valueOf(value)

    fun encodeExecutionPlan(plan: ExecutionPlan?): String? = plan?.let { planAdapter.toJson(it) }
    fun decodeExecutionPlan(json: String?): ExecutionPlan? =
        json?.let { runCatching { planAdapter.fromJson(it) }.getOrNull() }

    fun encodeTaskSummary(summary: TaskSummary?): String? = summary?.let { summaryAdapter.toJson(it) }
    fun decodeTaskSummary(json: String?): TaskSummary? =
        json?.let { runCatching { summaryAdapter.fromJson(it) }.getOrNull() }

    fun encodeStringList(list: List<String>): String = stringListAdapter.toJson(list)
    fun decodeStringList(json: String?): List<String> =
        json?.let { runCatching { stringListAdapter.fromJson(it) }.getOrNull() } ?: emptyList()
}
