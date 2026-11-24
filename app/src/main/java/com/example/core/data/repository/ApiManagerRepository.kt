package com.example.core.data.repository

import com.example.core.ai.ProviderType
import com.example.core.data.local.ApiKeyEntity
import com.example.core.data.local.ApiManagerDao
import com.example.core.data.local.ApiProviderEntity
import com.example.core.data.local.ProviderWithKeys
import com.example.core.security.SandboxGuard
import com.example.core.security.SecretCipher
import com.example.core.security.maskApiKey
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Repository managing unlimited AI providers and unlimited encrypted API keys,
 * including priority ordering, active/inactive toggles, quota cooldowns, and usage stats.
 */
class ApiManagerRepository(
    private val apiManagerDao: ApiManagerDao,
    private val secretCipher: SecretCipher
) {

    fun observeProviders(): Flow<List<ProviderWithKeys>> = apiManagerDao.observeProvidersWithKeys()

    suspend fun getProvidersSnapshot(): List<ProviderWithKeys> = apiManagerDao.getProvidersWithKeysOnce()

    /**
     * Adds a new AI provider configuration. Validates endpoint URL for HTTPS compliance.
     */
    suspend fun addProvider(
        name: String,
        providerType: ProviderType,
        baseUrl: String,
        modelPriority: List<String>,
        priority: Int
    ): Result<ApiProviderEntity> = runCatching {
        val cleanName = name.trim()
        require(cleanName.isNotBlank() && cleanName.length <= 64) {
            "Le nom du fournisseur doit contenir entre 1 et 64 caractères."
        }
        val effectiveUrl = baseUrl.trim().ifBlank { providerType.defaultBaseUrl }
        val validatedUrl = SandboxGuard.validateHttpsEndpoint(effectiveUrl).getOrThrow()

        val models = modelPriority.map { it.trim() }.filter { it.isNotBlank() }
        require(models.isNotEmpty()) {
            "Au moins un modèle prioritaire doit être configuré."
        }

        val now = System.currentTimeMillis()
        val entity = ApiProviderEntity(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            providerType = providerType.name,
            baseUrl = validatedUrl,
            modelPriorityCsv = models.joinToString(","),
            priority = priority.coerceIn(1, 999),
            enabled = true,
            createdAt = now,
            updatedAt = now
        )
        apiManagerDao.upsertProvider(entity)
        entity
    }

    suspend fun updateProviderConfig(
        providerId: String,
        name: String,
        baseUrl: String,
        modelPriority: List<String>,
        priority: Int
    ): Result<Unit> = runCatching {
        val existing = apiManagerDao.getProviderById(providerId)
            ?: throw IllegalArgumentException("Fournisseur introuvable.")
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Nom requis." }
        val validatedUrl = SandboxGuard.validateHttpsEndpoint(baseUrl).getOrThrow()
        val models = modelPriority.map { it.trim() }.filter { it.isNotBlank() }
        require(models.isNotEmpty()) { "Au moins un modèle requis." }

        apiManagerDao.upsertProvider(
            existing.copy(
                name = cleanName,
                baseUrl = validatedUrl,
                modelPriorityCsv = models.joinToString(","),
                priority = priority.coerceIn(1, 999),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) {
        apiManagerDao.setProviderEnabled(providerId, enabled, System.currentTimeMillis())
    }

    suspend fun deleteProvider(providerId: String) {
        apiManagerDao.deleteProviderById(providerId)
    }

    /**
     * Encrypts and stores a new API key for [providerId]. Plaintext is never written to SQLite.
     */
    suspend fun addApiKey(
        providerId: String,
        label: String,
        rawSecretKey: String,
        priority: Int = 100
    ): Result<ApiKeyEntity> = runCatching {
        val trimmedKey = rawSecretKey.trim()
        require(trimmedKey.length >= 10) {
            "La clé API semble trop courte (minimum 10 caractères)."
        }
        require(!trimmedKey.contains(" ") && !trimmedKey.contains("\n")) {
            "La clé API ne doit contenir ni espace ni retour à la ligne."
        }
        val cleanLabel = label.trim().ifBlank { "Clé ${maskApiKey(trimmedKey)}" }
        val encrypted = secretCipher.encrypt(trimmedKey)

        val entity = ApiKeyEntity(
            id = UUID.randomUUID().toString(),
            providerId = providerId,
            label = cleanLabel,
            encryptedKey = encrypted,
            maskedKey = maskApiKey(trimmedKey),
            enabled = true,
            priority = priority.coerceIn(1, 999),
            createdAt = System.currentTimeMillis()
        )
        apiManagerDao.upsertKey(entity)
        entity
    }

    suspend fun setKeyEnabled(keyId: String, enabled: Boolean) {
        apiManagerDao.setKeyEnabled(keyId, enabled)
    }

    suspend fun deleteKey(keyId: String) {
        apiManagerDao.deleteKeyById(keyId)
    }

    /**
     * Decrypts a stored key in memory right before an outbound HTTPS request.
     */
    fun decryptKey(keyEntity: ApiKeyEntity): String = secretCipher.decrypt(keyEntity.encryptedKey)

    /**
     * Records success metrics for a key (latency, tokens, request count).
     */
    suspend fun recordKeySuccess(
        keyId: String,
        latencyMs: Long,
        tokensUsed: Long,
        statusMessage: String = "Opérationnel"
    ) {
        val key = apiManagerDao.getKeyById(keyId) ?: return
        val newRequests = key.totalRequests + 1
        val newAvgLatency = if (key.totalRequests == 0L) {
            latencyMs
        } else {
            ((key.avgLatencyMs * key.totalRequests) + latencyMs) / newRequests
        }
        apiManagerDao.updateKey(
            key.copy(
                totalRequests = newRequests,
                totalTokens = key.totalTokens + tokensUsed.coerceAtLeast(0L),
                avgLatencyMs = newAvgLatency,
                lastStatus = statusMessage,
                lastError = null,
                quotaResetAtEpochMs = null,
                lastUsedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Records a key error and optionally places the key in cooldown if rate-limited / quota-exhausted.
     */
    suspend fun recordKeyError(
        keyId: String,
        statusLabel: String,
        errorMessage: String,
        cooldownMs: Long? = null,
        disableKey: Boolean = false
    ) {
        val key = apiManagerDao.getKeyById(keyId) ?: return
        val now = System.currentTimeMillis()
        apiManagerDao.updateKey(
            key.copy(
                totalRequests = key.totalRequests + 1,
                totalErrors = key.totalErrors + 1,
                enabled = if (disableKey) false else key.enabled,
                lastStatus = statusLabel,
                lastError = errorMessage.take(300),
                quotaResetAtEpochMs = cooldownMs?.let { now + it },
                lastUsedAt = now
            )
        )
    }
}
