package com.example.core.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Persisted plugin tool record storing the serialized [com.example.domain.manifest.ToolManifest]
 * alongside metadata for filtering, versioning, and auditability.
 */
@Entity(tableName = "tools")
data class ToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val iconName: String,
    val version: String,
    val author: String,
    val status: String,
    val manifestJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val executionCount: Int = 0
)

/**
 * Immutable version & changelog history entry for each tool modification.
 */
@Entity(
    tableName = "tool_changelogs",
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("toolId")]
)
data class ToolChangelogEntity(
    @PrimaryKey val id: String,
    val toolId: String,
    val version: String,
    val author: String,
    val status: String,
    val summary: String,
    val details: String,
    val timestamp: Long
)

/**
 * Execution audit entry recorded every time a user runs a sandboxed tool.
 */
@Entity(
    tableName = "tool_execution_logs",
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("toolId")]
)
data class ToolExecutionLogEntity(
    @PrimaryKey val id: String,
    val toolId: String,
    val toolVersion: String,
    val success: Boolean,
    val durationMs: Long,
    val providerUsed: String,
    val summary: String,
    val errorMessage: String? = null,
    val timestamp: Long
)

/**
 * Sandboxed per-tool key-value storage isolated strictly by [toolId].
 */
@Entity(
    tableName = "tool_storage_entries",
    primaryKeys = ["toolId", "storageKey"],
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("toolId")]
)
data class ToolStorageEntryEntity(
    val toolId: String,
    val storageKey: String,
    val value: String,
    val updatedAt: Long
)

/**
 * User-configured LLM API provider (unlimited providers supported).
 */
@Entity(tableName = "api_providers")
data class ApiProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val providerType: String,
    val baseUrl: String,
    val modelPriorityCsv: String,
    val priority: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Encrypted API key entry belonging to an [ApiProviderEntity] (unlimited keys per provider).
 */
@Entity(
    tableName = "api_keys",
    foreignKeys = [
        ForeignKey(
            entity = ApiProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("providerId")]
)
data class ApiKeyEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val label: String,
    val encryptedKey: String,
    val maskedKey: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val totalRequests: Long = 0,
    val totalErrors: Long = 0,
    val totalTokens: Long = 0,
    val avgLatencyMs: Long = 0,
    val lastStatus: String = "Non testée",
    val lastError: String? = null,
    val quotaResetAtEpochMs: Long? = null,
    val lastUsedAt: Long? = null,
    val createdAt: Long
)

/**
 * Room relation pairing a provider with all of its configured encrypted API keys.
 */
data class ProviderWithKeys(
    @Embedded val provider: ApiProviderEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "providerId"
    )
    val keys: List<ApiKeyEntity>
)
