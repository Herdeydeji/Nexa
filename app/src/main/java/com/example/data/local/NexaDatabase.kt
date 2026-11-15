package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AgentTaskEntity::class,
        TaskStepEntity::class
    ],
    version = 2,
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
        // Phase 2 removes the static Phase 1 YouTube chat preview so Chat launches cleanly into live AI chat.
        convDao.removePreviewConversations()
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

        fun build(context: Context): NexaDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                NexaDatabase::class.java,
                "nexa_phase1.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
