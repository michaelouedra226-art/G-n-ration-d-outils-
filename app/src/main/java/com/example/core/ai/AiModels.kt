package com.example.core.ai

/**
 * Supported AI provider types in ForgeAI's unlimited API Manager.
 */
enum class ProviderType(
    val displayName: String,
    val protocol: ProviderProtocol,
    val defaultBaseUrl: String,
    val defaultModels: List<String>,
    val description: String
) {
    OPENAI(
        displayName = "OpenAI",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModels = listOf("gpt-4o-mini", "gpt-4o", "o3-mini"),
        description = "API officielle OpenAI (chat, JSON structuré, vision)"
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModels = listOf("claude-3-5-sonnet-latest", "claude-3-5-haiku-latest"),
        description = "Claude Messages API (raisonnement, rédaction, analyse)"
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModels = listOf("deepseek-chat", "deepseek-reasoner"),
        description = "API DeepSeek compatible OpenAI"
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModels = listOf("google/gemini-2.5-flash", "openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet"),
        description = "Passerelle multi-modèles (Gemini, Claude, GPT, Llama)"
    ),
    AGNES_AI(
        displayName = "Agnes AI",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.agnes-ai.com/v1",
        defaultModels = listOf("agnes-pro", "agnes-fast"),
        description = "Fournisseur Agnes AI (endpoint compatible OpenAI configurable)"
    ),
    OPENAI_COMPATIBLE(
        displayName = "Compatible OpenAI",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.example.com/v1",
        defaultModels = listOf("default-model"),
        description = "Tout serveur compatible /v1/chat/completions en HTTPS"
    ),
    GEMINI(
        displayName = "Gemini (Firebase AI Logic)",
        protocol = ProviderProtocol.FIREBASE_AI_PLACEHOLDER,
        defaultBaseUrl = "https://firebase.google.com/docs/ai-logic",
        defaultModels = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-argon"),
        description = "Gemini natif via Firebase AI Logic (non activé dans ce projet)"
    )
}

enum class ProviderProtocol {
    OPENAI_COMPATIBLE,
    ANTHROPIC_MESSAGES,
    FIREBASE_AI_PLACEHOLDER
}

/**
 * Strategy used by [ApiRouterService] to distribute requests across keys and providers.
 */
enum class LoadBalancingStrategy(val label: String, val description: String) {
    PRIORITY_AND_ROTATION(
        label = "Priorité + Rotation auto",
        description = "Utilise la clé la plus prioritaire et bascule automatiquement en cas de quota ou d'erreur"
    ),
    ROUND_ROBIN(
        label = "Round-Robin",
        description = "Répartit équitablement la charge entre toutes les clés actives"
    ),
    LEAST_ERRORS(
        label = "Fiabilité maximale",
        description = "Privilégie les clés ayant le taux d'erreur le plus faible"
    ),
    FASTEST_LATENCY(
        label = "Latence minimale",
        description = "Sélectionne la clé active avec le temps de réponse moyen le plus rapide"
    )
}

/**
 * Single conversation message for multi-turn chat plugins.
 */
data class AiMessage(
    val role: String,
    val content: String
)

/**
 * Normalized AI completion request passed to [AiGateway].
 */
data class AiRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val preferredModel: String = "",
    val temperature: Double = 0.4,
    val maxTokens: Int = 2048,
    val jsonMode: Boolean = false,
    val imageBase64: String? = null,
    val imageMimeType: String = "image/jpeg",
    val conversationHistory: List<AiMessage> = emptyList()
)

/**
 * Normalized AI completion response returned by [AiGateway].
 */
data class AiResponse(
    val content: String,
    val modelUsed: String,
    val providerName: String,
    val keyLabel: String,
    val latencyMs: Long,
    val tokensUsed: Long
)

/**
 * Resolved endpoint + decrypted key material held briefly in memory for a single request.
 */
data class ResolvedEndpoint(
    val providerId: String,
    val providerName: String,
    val providerType: ProviderType,
    val baseUrl: String,
    val model: String,
    val keyId: String,
    val keyLabel: String,
    val apiKey: String
)

/**
 * Result of testing a specific provider key connection.
 */
data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val modelResponded: String? = null
)

/**
 * Classified AI error categories for clean UI reporting and smart key rotation.
 */
enum class AiErrorKind(val retryableWithNextKey: Boolean) {
    OFFLINE(false),
    QUOTA_EXCEEDED(true),
    INVALID_API_KEY(true),
    PERMISSION_DENIED(true),
    TIMEOUT(true),
    SERVER_ERROR(true),
    NO_PROVIDER_CONFIGURED(false),
    FIREBASE_REQUIRED(false),
    INVALID_RESPONSE(false)
}

class AiGatewayException(
    val kind: AiErrorKind,
    override val message: String,
    val httpCode: Int? = null,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause)
