package com.example.core.ai

import com.example.core.data.local.ApiKeyEntity
import com.example.core.data.local.ProviderWithKeys
import com.example.core.data.repository.ApiManagerRepository
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Intelligent multi-provider, multi-key router implementing:
 * - automatic key rotation and failover
 * - load balancing ([LoadBalancingStrategy])
 * - quota/rate-limit cooldown detection (HTTP 429)
 * - invalid key detection (HTTP 401/403)
 * - model priority selection and exponential retry
 * - real-time per-key telemetry updates
 */
class ApiRouterService(
    private val apiManagerRepository: ApiManagerRepository,
    private val aiGateway: AiGateway,
    private val builtInGeminiNotice: BuiltInGeminiNoticeGateway = BuiltInGeminiNoticeGateway()
) {
    private val roundRobinCursor = AtomicInteger(0)

    /**
     * Executes [request] across active providers and keys using [strategy].
     * Rotates automatically to fallback keys/providers on quota, auth, or transient server failures.
     */
    suspend fun executeWithFailover(
        request: AiRequest,
        strategy: LoadBalancingStrategy = LoadBalancingStrategy.PRIORITY_AND_ROTATION
    ): Result<AiResponse> {
        val providers = apiManagerRepository.getProvidersSnapshot()
        val now = System.currentTimeMillis()
        val candidates = buildOrderedCandidates(providers, request.preferredModel, strategy, now)

        if (candidates.isEmpty()) {
            val hasGeminiNoticeOnly = providers.any {
                it.provider.enabled &&
                    ProviderType.entries.firstOrNull { t -> t.name == it.provider.providerType } == ProviderType.GEMINI
            }
            return if (hasGeminiNoticeOnly || providers.isEmpty()) {
                Result.failure(
                    AiGatewayException(
                        kind = if (hasGeminiNoticeOnly) AiErrorKind.FIREBASE_REQUIRED else AiErrorKind.NO_PROVIDER_CONFIGURED,
                        message = if (hasGeminiNoticeOnly) {
                            builtInGeminiNotice.unavailableMessage()
                        } else {
                            "Aucun fournisseur IA actif avec clé API valide. Ajoutez une clé dans l'onglet API."
                        }
                    )
                )
            } else {
                Result.failure(
                    AiGatewayException(
                        kind = AiErrorKind.NO_PROVIDER_CONFIGURED,
                        message = "Toutes les clés API configurées sont désactivées ou en pause de quota."
                    )
                )
            }
        }

        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            val decryptedKey = runCatching {
                apiManagerRepository.decryptKey(candidate.key)
            }.getOrElse { error ->
                apiManagerRepository.recordKeyError(
                    keyId = candidate.key.id,
                    statusLabel = "Erreur déchiffrement",
                    errorMessage = error.localizedMessage ?: "Clé corrompue"
                )
                lastError = error
                continue
            }

            val endpoint = ResolvedEndpoint(
                providerId = candidate.provider.provider.id,
                providerName = candidate.provider.provider.name,
                providerType = candidate.providerType,
                baseUrl = candidate.provider.provider.baseUrl,
                model = candidate.model,
                keyId = candidate.key.id,
                keyLabel = candidate.key.label,
                apiKey = decryptedKey
            )

            val attempt = aiGateway.complete(request, endpoint)
            if (attempt.isSuccess) {
                val response = attempt.getOrThrow()
                apiManagerRepository.recordKeySuccess(
                    keyId = candidate.key.id,
                    latencyMs = response.latencyMs,
                    tokensUsed = response.tokensUsed
                )
                return Result.success(response)
            }

            val failure = attempt.exceptionOrNull()
            lastError = failure
            val gwError = failure as? AiGatewayException

            when (gwError?.kind) {
                AiErrorKind.OFFLINE -> {
                    // Do not penalize keys when device is offline
                    return Result.failure(gwError)
                }
                AiErrorKind.QUOTA_EXCEEDED -> {
                    val cooldown = (gwError.retryAfterMs ?: QUOTA_COOLDOWN_MS)
                        .coerceIn(5_000L, 300_000L)
                    apiManagerRepository.recordKeyError(
                        keyId = candidate.key.id,
                        statusLabel = "Quota / 429 (${cooldown / 1000}s)",
                        errorMessage = gwError.message,
                        cooldownMs = cooldown
                    )
                }
                AiErrorKind.INVALID_API_KEY, AiErrorKind.PERMISSION_DENIED -> {
                    apiManagerRepository.recordKeyError(
                        keyId = candidate.key.id,
                        statusLabel = "Clé invalide",
                        errorMessage = gwError.message
                    )
                }
                AiErrorKind.TIMEOUT, AiErrorKind.SERVER_ERROR -> {
                    apiManagerRepository.recordKeyError(
                        keyId = candidate.key.id,
                        statusLabel = "Erreur réseau/serveur",
                        errorMessage = gwError.message
                    )
                    if (index < candidates.lastIndex) {
                        delay(RETRY_BACKOFF_MS)
                    }
                }
                else -> {
                    apiManagerRepository.recordKeyError(
                        keyId = candidate.key.id,
                        statusLabel = "Erreur",
                        errorMessage = failure?.message ?: "Échec inconnu"
                    )
                }
            }

            if (gwError != null && !gwError.kind.retryableWithNextKey) {
                return Result.failure(gwError)
            }
        }

        return Result.failure(
            lastError ?: AiGatewayException(
                kind = AiErrorKind.SERVER_ERROR,
                message = "Toutes les clés disponibles ont échoué."
            )
        )
    }

    /**
     * Tests a single key directly against its provider's primary model and persists the diagnostic status.
     */
    suspend fun testSingleKey(providerId: String, keyId: String): ConnectionTestResult {
        val snapshot = apiManagerRepository.getProvidersSnapshot()
        val pwk = snapshot.firstOrNull { it.provider.id == providerId }
            ?: return ConnectionTestResult(false, 0, "Fournisseur introuvable.")
        val key = pwk.keys.firstOrNull { it.id == keyId }
            ?: return ConnectionTestResult(false, 0, "Clé introuvable.")
        val providerType = ProviderType.entries.firstOrNull { it.name == pwk.provider.providerType }
            ?: ProviderType.OPENAI_COMPATIBLE
        val model = pwk.provider.modelPriorityCsv.split(",").map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?: providerType.defaultModels.first()

        val rawKey = runCatching { apiManagerRepository.decryptKey(key) }.getOrElse {
            return ConnectionTestResult(false, 0, "Impossible de déchiffrer la clé dans le Keystore.")
        }

        val endpoint = ResolvedEndpoint(
            providerId = pwk.provider.id,
            providerName = pwk.provider.name,
            providerType = providerType,
            baseUrl = pwk.provider.baseUrl,
            model = model,
            keyId = key.id,
            keyLabel = key.label,
            apiKey = rawKey
        )

        val result = aiGateway.testConnection(endpoint)
        if (result.success) {
            apiManagerRepository.recordKeySuccess(
                keyId = key.id,
                latencyMs = result.latencyMs,
                tokensUsed = 0L,
                statusMessage = "Validée (${result.latencyMs} ms)"
            )
        } else {
            apiManagerRepository.recordKeyError(
                keyId = key.id,
                statusLabel = "Test échoué",
                errorMessage = result.message
            )
        }
        return result
    }

    /**
     * Queries the provider's `/models` endpoint using its highest-priority active key.
     */
    suspend fun fetchAvailableModels(providerId: String): Result<List<String>> {
        val snapshot = apiManagerRepository.getProvidersSnapshot()
        val pwk = snapshot.firstOrNull { it.provider.id == providerId }
            ?: return Result.failure(IllegalArgumentException("Fournisseur introuvable."))
        val providerType = ProviderType.entries.firstOrNull { it.name == pwk.provider.providerType }
            ?: ProviderType.OPENAI_COMPATIBLE
        if (providerType.protocol == ProviderProtocol.FIREBASE_AI_PLACEHOLDER) {
            return Result.failure(builtInGeminiNotice.asError())
        }
        val key = pwk.keys.filter { it.enabled }.minByOrNull { it.priority }
            ?: pwk.keys.minByOrNull { it.priority }
            ?: return Result.failure(IllegalStateException("Ajoutez au moins une clé API à ce fournisseur pour interroger ses modèles."))

        val rawKey = runCatching { apiManagerRepository.decryptKey(key) }.getOrElse {
            return Result.failure(IllegalStateException("Impossible de déchiffrer la clé dans le Keystore."))
        }
        val endpoint = ResolvedEndpoint(
            providerId = pwk.provider.id,
            providerName = pwk.provider.name,
            providerType = providerType,
            baseUrl = pwk.provider.baseUrl,
            model = providerType.defaultModels.firstOrNull().orEmpty(),
            keyId = key.id,
            keyLabel = key.label,
            apiKey = rawKey
        )
        return aiGateway.fetchAvailableModels(endpoint)
    }

    private data class Candidate(
        val provider: ProviderWithKeys,
        val providerType: ProviderType,
        val key: ApiKeyEntity,
        val model: String
    )

    private fun buildOrderedCandidates(
        providers: List<ProviderWithKeys>,
        preferredModel: String,
        strategy: LoadBalancingStrategy,
        nowMs: Long
    ): List<Candidate> {
        val rawCandidates = mutableListOf<Candidate>()
        for (pwk in providers.sortedBy { it.provider.priority }) {
            if (!pwk.provider.enabled) continue
            val providerType = ProviderType.entries.firstOrNull { it.name == pwk.provider.providerType }
                ?: continue
            if (providerType.protocol == ProviderProtocol.FIREBASE_AI_PLACEHOLDER) continue

            val models = pwk.provider.modelPriorityCsv
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val selectedModel = if (preferredModel.isNotBlank() && models.any { it.equals(preferredModel, ignoreCase = true) }) {
                models.first { it.equals(preferredModel, ignoreCase = true) }
            } else {
                models.firstOrNull() ?: continue
            }

            val eligibleKeys = pwk.keys.filter { key ->
                key.enabled && (key.quotaResetAtEpochMs == null || key.quotaResetAtEpochMs <= nowMs)
            }
            for (key in eligibleKeys) {
                rawCandidates.add(Candidate(pwk, providerType, key, selectedModel))
            }
        }

        if (rawCandidates.isEmpty()) return emptyList()

        return when (strategy) {
            LoadBalancingStrategy.PRIORITY_AND_ROTATION -> {
                rawCandidates.sortedWith(
                    compareBy<Candidate> { it.provider.provider.priority }
                        .thenBy { it.key.priority }
                        .thenBy { it.key.totalErrors }
                )
            }
            LoadBalancingStrategy.ROUND_ROBIN -> {
                val size = rawCandidates.size
                val start = Math.floorMod(roundRobinCursor.getAndIncrement(), size)
                (0 until size).map { offset -> rawCandidates[(start + offset) % size] }
            }
            LoadBalancingStrategy.LEAST_ERRORS -> {
                rawCandidates.sortedWith(
                    compareBy<Candidate> { it.key.totalErrors }
                        .thenBy { it.provider.provider.priority }
                        .thenBy { it.key.priority }
                )
            }
            LoadBalancingStrategy.FASTEST_LATENCY -> {
                rawCandidates.sortedWith(
                    compareBy<Candidate> { if (it.key.avgLatencyMs == 0L) Long.MAX_VALUE else it.key.avgLatencyMs }
                        .thenBy { it.provider.provider.priority }
                )
            }
        }
    }

    companion object {
        private const val QUOTA_COOLDOWN_MS = 60_000L
        private const val RETRY_BACKOFF_MS = 350L
    }
}
