package com.example.core.data.repository

import com.example.core.data.local.ToolChangelogEntity
import com.example.core.data.local.ToolDao
import com.example.core.data.local.ToolEntity
import com.example.core.data.local.ToolExecutionLogEntity
import com.example.core.data.local.ToolStorageEntryEntity
import com.example.core.security.SandboxGuard
import com.example.domain.manifest.ManifestValidator
import com.example.domain.manifest.ToolCategory
import com.example.domain.manifest.ToolManifest
import com.example.domain.manifest.ToolStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Domain projection pairing a persisted [ToolEntity] with its parsed [ToolManifest].
 */
data class InstalledTool(
    val entity: ToolEntity,
    val manifest: ToolManifest
)

/**
 * Repository managing user-created plugins, version changelogs, execution logs,
 * and sandboxed per-tool key-value storage.
 */
class ToolRepository(
    private val toolDao: ToolDao,
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
) {
    private val manifestAdapter = moshi.adapter(ToolManifest::class.java).indent("  ")

    fun observeTools(): Flow<List<InstalledTool>> =
        toolDao.observeAllTools().map { entities ->
            entities.mapNotNull { entity ->
                parseManifest(entity.manifestJson)?.let { manifest ->
                    InstalledTool(entity = entity, manifest = manifest)
                }
            }
        }

    fun observeTool(toolId: String): Flow<InstalledTool?> =
        toolDao.observeToolById(toolId).map { entity ->
            entity?.let {
                parseManifest(it.manifestJson)?.let { manifest ->
                    InstalledTool(entity = it, manifest = manifest)
                }
            }
        }

    fun observeChangelogs(toolId: String): Flow<List<ToolChangelogEntity>> =
        toolDao.observeChangelogsForTool(toolId)

    fun observeExecutionLogs(toolId: String): Flow<List<ToolExecutionLogEntity>> =
        toolDao.observeLogsForTool(toolId)

    fun observeToolStorage(toolId: String): Flow<List<ToolStorageEntryEntity>> =
        toolDao.observeStorageForTool(toolId)

    suspend fun getTool(toolId: String): InstalledTool? {
        val entity = toolDao.getToolById(toolId) ?: return null
        val manifest = parseManifest(entity.manifestJson) ?: return null
        return InstalledTool(entity, manifest)
    }

    /**
     * Validates and saves a newly forged or updated tool plugin along with an audit changelog entry.
     */
    suspend fun saveTool(
        manifest: ToolManifest,
        changelogSummary: String,
        changelogDetails: String = ""
    ): Result<InstalledTool> = runCatching {
        val validated = ManifestValidator.validate(manifest).getOrThrow()
        val now = System.currentTimeMillis()
        val existing = toolDao.getToolById(validated.id)
        val json = manifestAdapter.toJson(validated)

        val entity = ToolEntity(
            id = validated.id,
            name = validated.name,
            description = validated.description,
            category = validated.category.name,
            iconName = validated.iconName,
            version = validated.version,
            author = validated.author,
            status = validated.status.name,
            manifestJson = json,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            executionCount = existing?.executionCount ?: 0
        )
        toolDao.upsertTool(entity)
        toolDao.insertChangelog(
            ToolChangelogEntity(
                id = UUID.randomUUID().toString(),
                toolId = validated.id,
                version = validated.version,
                author = validated.author,
                status = validated.status.name,
                summary = changelogSummary.ifBlank {
                    if (existing == null) "Création initiale du plugin" else "Mise à jour du manifeste"
                },
                details = changelogDetails,
                timestamp = now
            )
        )
        InstalledTool(entity, validated)
    }

    /**
     * Updates an existing tool from edited JSON manifest text, bumping version if unchanged.
     */
    suspend fun updateToolFromJson(
        toolId: String,
        rawJson: String,
        changelogSummary: String
    ): Result<InstalledTool> = runCatching {
        val parsed = manifestAdapter.fromJson(rawJson)
            ?: throw IllegalArgumentException("JSON du manifeste invalide ou vide.")
        val normalized = parsed.copy(id = toolId)
        saveTool(normalized, changelogSummary, "Édition manuelle du manifeste JSON").getOrThrow()
    }

    /**
     * Imports a validated [ToolManifest] from an external JSON file, assigning a fresh safe ID if needed.
     */
    suspend fun importToolFromJson(rawJson: String): Result<InstalledTool> = runCatching {
        val parsed = manifestAdapter.fromJson(rawJson)
            ?: throw IllegalArgumentException("Fichier JSON non reconnu comme un manifeste ForgeAI.")
        val existing = parsed.id.takeIf { it.isNotBlank() }?.let { toolDao.getToolById(it) }
        val targetId = if (parsed.id.isBlank() || existing != null) {
            "tool_${UUID.randomUUID().toString().take(8)}"
        } else {
            parsed.id
        }
        val normalized = parsed.copy(id = targetId, status = ToolStatus.ACTIVE)
        saveTool(
            manifest = normalized,
            changelogSummary = "Importé depuis fichier JSON",
            changelogDetails = "Manifeste v${normalized.version} validé par SandboxGuard"
        ).getOrThrow()
    }

    /**
     * Updates a tool's lifecycle status (Active, Paused, Draft, Archived) and records changelog.
     */
    suspend fun updateToolStatus(toolId: String, newStatus: ToolStatus): Result<Unit> = runCatching {
        val current = getTool(toolId) ?: throw IllegalArgumentException("Outil introuvable.")
        val updatedManifest = current.manifest.copy(status = newStatus)
        saveTool(
            manifest = updatedManifest,
            changelogSummary = "Statut modifié : ${newStatus.label}"
        ).getOrThrow()
    }

    suspend fun deleteTool(toolId: String) {
        toolDao.deleteToolById(toolId)
    }

    suspend fun recordExecution(
        toolId: String,
        toolVersion: String,
        success: Boolean,
        durationMs: Long,
        providerUsed: String,
        summary: String,
        errorMessage: String? = null
    ) {
        val now = System.currentTimeMillis()
        toolDao.incrementExecutionCount(toolId, now)
        toolDao.insertExecutionLog(
            ToolExecutionLogEntity(
                id = UUID.randomUUID().toString(),
                toolId = toolId,
                toolVersion = toolVersion,
                success = success,
                durationMs = durationMs,
                providerUsed = providerUsed,
                summary = summary.take(240),
                errorMessage = errorMessage?.take(400),
                timestamp = now
            )
        )
    }

    suspend fun readSandboxedStorage(toolId: String, key: String): String? {
        val safeKey = SandboxGuard.validateStorageKey(key)
        return toolDao.getStorageValue(toolId, safeKey)
    }

    suspend fun writeSandboxedStorage(toolId: String, key: String, value: String) {
        val safeKey = SandboxGuard.validateStorageKey(key)
        require(value.length <= SandboxGuard.MAX_STORAGE_VALUE_LENGTH) {
            "Valeur trop volumineuse pour le stockage sandboxé."
        }
        toolDao.upsertStorageEntry(
            ToolStorageEntryEntity(
                toolId = toolId,
                storageKey = safeKey,
                value = value,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearToolStorage(toolId: String) {
        toolDao.clearToolStorage(toolId)
    }

    fun serializeManifest(manifest: ToolManifest): String = manifestAdapter.toJson(manifest)

    fun parseManifest(json: String): ToolManifest? = runCatching {
        manifestAdapter.fromJson(json)
    }.getOrNull()

    fun parseCategory(raw: String): ToolCategory =
        ToolCategory.entries.firstOrNull { it.name == raw } ?: ToolCategory.CUSTOM

    fun parseStatus(raw: String): ToolStatus =
        ToolStatus.entries.firstOrNull { it.name == raw } ?: ToolStatus.ACTIVE
}
