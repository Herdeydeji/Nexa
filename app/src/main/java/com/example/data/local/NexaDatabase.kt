package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.model.AgentTaskStatus

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AgentTaskEntity::class,
        TaskStepEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(NexaTypeConverters::class)
abstract class NexaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun taskDao(): TaskDao

    suspend fun initializePhase2DataIfNeeded(converters: NexaTypeConverters) {
        val convDao = conversationDao()
        val tDao = taskDao()
        val now = System.currentTimeMillis()
        convDao.removePreviewConversations()
        // Any live task left in an active/waiting state from a killed process is reconciled honestly.
        tDao.getActiveLiveTasks().forEach { stale ->
            val wasUnlockWait = stale.status == AgentTaskStatus.WAITING_FOR_USER_UNLOCK
            tDao.updateTaskOutcome(
                taskId = stale.id,
                status = if (wasUnlockWait && stale.resumableSnapshotJson != null) {
                    AgentTaskStatus.PAUSED
                } else {
                    AgentTaskStatus.FAILED
                },
                failureReason = if (wasUnlockWait && stale.resumableSnapshotJson != null) {
                    "AppLock wait was interrupted when the app closed. Re-run or resume when unlocked."
                } else {
                    "Interrupted before completion when the app restarted."
                },
                summaryMessage = stale.summaryMessage,
                verificationJson = stale.verificationJson,
                updatedAt = now
            )
        }
        if (tDao.countTasks() == 0) {
            SampleSeedData.buildSampleTasks(now, converters).forEach { (task, steps) ->
                tDao.insertTaskWithSteps(task, steps)
            }
        }
    }

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETE'")
                db.execSQL("ALTER TABLE messages ADD COLUMN errorMessage TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN providerLabel TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN modelLabel TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN completionTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN totalTokens INTEGER")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallsJson TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolName TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN verificationJson TEXT")
                db.execSQL("ALTER TABLE agent_tasks ADD COLUMN summaryMessage TEXT")
                db.execSQL("ALTER TABLE agent_tasks ADD COLUMN verificationJson TEXT")
                db.execSQL("ALTER TABLE task_steps ADD COLUMN kind TEXT NOT NULL DEFAULT 'EXECUTED'")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_tasks ADD COLUMN resumableSnapshotJson TEXT")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN preferredProviderId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN preferredModelId TEXT")
            }
        }

        fun build(context: Context): NexaDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                NexaDatabase::class.java,
                "nexa_phase1.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }
    }
}
