package com.example.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local SQLite database for ForgeAI. Starts completely empty on fresh installation;
 * all records are created exclusively from explicit user actions.
 */
@Database(
    entities = [
        ToolEntity::class,
        ToolChangelogEntity::class,
        ToolExecutionLogEntity::class,
        ToolStorageEntryEntity::class,
        ApiProviderEntity::class,
        ApiKeyEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun toolDao(): ToolDao
    abstract fun apiManagerDao(): ApiManagerDao

    companion object {
        private const val DATABASE_NAME = "forge_ai_database.db"

        fun create(context: Context): ForgeDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                ForgeDatabase::class.java,
                DATABASE_NAME
            ).build()
        }
    }
}
