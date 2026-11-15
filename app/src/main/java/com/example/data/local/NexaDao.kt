package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.domain.model.AgentTaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtMillis DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun countConversations(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAtMillis = :updatedAt WHERE id = :id")
    suspend fun renameConversation(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET lastMessagePreview = :preview, updatedAtMillis = :updatedAt WHERE id = :id")
    suspend fun updateConversationPreview(id: String, preview: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM conversations WHERE isSamplePreview = 1")
    suspend fun removePreviewConversations()

    @Query("DELETE FROM conversations")
    suspend fun clearAllConversations()

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
    suspend fun getMessagesSnapshot(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY updatedAtMillis DESC")
    fun observeAllTasks(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :taskId LIMIT 1")
    fun observeTaskById(taskId: String): Flow<AgentTaskEntity?>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY stepOrder ASC")
    fun observeStepsForTask(taskId: String): Flow<List<TaskStepEntity>>

    @Query("SELECT * FROM task_steps WHERE taskId IN (:taskIds) ORDER BY stepOrder ASC")
    fun observeStepsForTasks(taskIds: List<String>): Flow<List<TaskStepEntity>>

    @Query("SELECT COUNT(*) FROM agent_tasks")
    suspend fun countTasks(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: AgentTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<TaskStepEntity>)

    @Query("UPDATE agent_tasks SET status = :status, updatedAtMillis = :updatedAt WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: String, status: AgentTaskStatus, updatedAt: Long)

    @Transaction
    suspend fun insertTaskWithSteps(task: AgentTaskEntity, steps: List<TaskStepEntity>) {
        upsertTask(task)
        insertSteps(steps)
    }
}
