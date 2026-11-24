package com.example.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolDao {
    @Query("SELECT * FROM tools ORDER BY updatedAt DESC")
    fun observeAllTools(): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools WHERE id = :toolId LIMIT 1")
    fun observeToolById(toolId: String): Flow<ToolEntity?>

    @Query("SELECT * FROM tools WHERE id = :toolId LIMIT 1")
    suspend fun getToolById(toolId: String): ToolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTool(tool: ToolEntity)

    @Query("UPDATE tools SET executionCount = executionCount + 1, updatedAt = :timestamp WHERE id = :toolId")
    suspend fun incrementExecutionCount(toolId: String, timestamp: Long)

    @Query("DELETE FROM tools WHERE id = :toolId")
    suspend fun deleteToolById(toolId: String)

    @Query("SELECT * FROM tool_changelogs WHERE toolId = :toolId ORDER BY timestamp DESC")
    fun observeChangelogsForTool(toolId: String): Flow<List<ToolChangelogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChangelog(entry: ToolChangelogEntity)

    @Query("SELECT * FROM tool_execution_logs WHERE toolId = :toolId ORDER BY timestamp DESC LIMIT 50")
    fun observeLogsForTool(toolId: String): Flow<List<ToolExecutionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecutionLog(log: ToolExecutionLogEntity)

    @Query("SELECT * FROM tool_storage_entries WHERE toolId = :toolId ORDER BY updatedAt DESC")
    fun observeStorageForTool(toolId: String): Flow<List<ToolStorageEntryEntity>>

    @Query("SELECT value FROM tool_storage_entries WHERE toolId = :toolId AND storageKey = :key LIMIT 1")
    suspend fun getStorageValue(toolId: String, key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStorageEntry(entry: ToolStorageEntryEntity)

    @Query("DELETE FROM tool_storage_entries WHERE toolId = :toolId")
    suspend fun clearToolStorage(toolId: String)
}

@Dao
interface ApiManagerDao {
    @Transaction
    @Query("SELECT * FROM api_providers ORDER BY priority ASC, createdAt ASC")
    fun observeProvidersWithKeys(): Flow<List<ProviderWithKeys>>

    @Transaction
    @Query("SELECT * FROM api_providers ORDER BY priority ASC, createdAt ASC")
    suspend fun getProvidersWithKeysOnce(): List<ProviderWithKeys>

    @Query("SELECT * FROM api_providers WHERE id = :providerId LIMIT 1")
    suspend fun getProviderById(providerId: String): ApiProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(provider: ApiProviderEntity)

    @Query("DELETE FROM api_providers WHERE id = :providerId")
    suspend fun deleteProviderById(providerId: String)

    @Query("SELECT * FROM api_keys WHERE id = :keyId LIMIT 1")
    suspend fun getKeyById(keyId: String): ApiKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKey(key: ApiKeyEntity)

    @Update
    suspend fun updateKey(key: ApiKeyEntity)

    @Query("DELETE FROM api_keys WHERE id = :keyId")
    suspend fun deleteKeyById(keyId: String)

    @Query("UPDATE api_keys SET enabled = :enabled WHERE id = :keyId")
    suspend fun setKeyEnabled(keyId: String, enabled: Boolean)

    @Query("UPDATE api_providers SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :providerId")
    suspend fun setProviderEnabled(providerId: String, enabled: Boolean, updatedAt: Long)
}
