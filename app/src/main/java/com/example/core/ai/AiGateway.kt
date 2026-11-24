package com.example.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Unified AI execution gateway routing all generative calls through a single interface.
 *
 * - Built-in Gemini slot uses [BuiltInGeminiNoticeGateway] when Firebase AI Logic is disabled.
 * - User-configured API providers execute real HTTPS requests via [OkHttpAiGateway].
 */
interface AiGateway {
    suspend fun complete(request: AiRequest, endpoint: ResolvedEndpoint): Result<AiResponse>
    suspend fun testConnection(endpoint: ResolvedEndpoint): ConnectionTestResult
}

/**
 * Placeholder gateway for the built-in Gemini slot when Firebase AI Logic was declined by the user.
 * Never embeds API keys or performs unauthorized direct Gemini calls.
 */
class BuiltInGeminiNoticeGateway {
    fun unavailableMessage(): String =
        "Gemini intégré requiert Firebase AI Logic (désactivé). Ajoutez un fournisseur compatible dans l'onglet API (ex. OpenRouter, OpenAI, Anthropic, DeepSeek) pour générer et exécuter des outils IA."

    fun asError(): AiGatewayException = AiGatewayException(
        kind = AiErrorKind.FIREBASE_REQUIRED,
        message = unavailableMessage()
    )
}

/**
 * Production OkHttp implementation supporting OpenAI-compatible (`/chat/completions`)
 * and Anthropic Messages (`/messages`) HTTPS endpoints.
 */
class OkHttpAiGateway(
    private val networkChecker: NetworkChecker,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : AiGateway {

    private val builtInGeminiNotice = BuiltInGeminiNoticeGateway()

    override suspend fun complete(request: AiRequest, endpoint: ResolvedEndpoint): Result<AiResponse> =
        withContext(Dispatchers.IO) {
            if (endpoint.providerType.protocol == ProviderProtocol.FIREBASE_AI_PLACEHOLDER) {
                return@withContext Result.failure(builtInGeminiNotice.asError())
            }
            if (!networkChecker.isOnline()) {
                return@withContext Result.failure(
                    AiGatewayException(
                        kind = AiErrorKind.OFFLINE,
                        message = "Hors ligne : aucune connexion Internet active."
                    )
                )
            }

            val startNs = System.nanoTime()
            try {
                val httpRequest = when (endpoint.providerType.protocol) {
                    ProviderProtocol.OPENAI_COMPATIBLE -> buildOpenAiRequest(request, endpoint)
                    ProviderProtocol.ANTHROPIC_MESSAGES -> buildAnthropicRequest(request, endpoint)
                    ProviderProtocol.FIREBASE_AI_PLACEHOLDER ->
                        return@withContext Result.failure(builtInGeminiNotice.asError())
                }

                client.newCall(httpRequest).execute().use { response ->
                    val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                    val rawBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            classifyHttpError(response.code, rawBody, endpoint.providerName)
                        )
                    }

                    val parsed = when (endpoint.providerType.protocol) {
                        ProviderProtocol.OPENAI_COMPATIBLE -> parseOpenAiResponse(rawBody, endpoint, latencyMs)
                        ProviderProtocol.ANTHROPIC_MESSAGES -> parseAnthropicResponse(rawBody, endpoint, latencyMs)
                        ProviderProtocol.FIREBASE_AI_PLACEHOLDER ->
                            throw builtInGeminiNotice.asError()
                    }
                    Result.success(parsed)
                }
            } catch (e: SocketTimeoutException) {
                Result.failure(
                    AiGatewayException(
                        kind = AiErrorKind.TIMEOUT,
                        message = "Délai dépassé auprès de ${endpoint.providerName}.",
                        cause = e
                    )
                )
            } catch (e: IOException) {
                Result.failure(
                    AiGatewayException(
                        kind = AiErrorKind.SERVER_ERROR,
                        message = "Erreur réseau (${endpoint.providerName}) : ${e.localizedMessage ?: "connexion interrompue"}",
                        cause = e
                    )
                )
            } catch (e: AiGatewayException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(
                    AiGatewayException(
                        kind = AiErrorKind.INVALID_RESPONSE,
                        message = "Réponse invalide de ${endpoint.providerName} : ${e.localizedMessage}",
                        cause = e
                    )
                )
            }
        }

    override suspend fun testConnection(endpoint: ResolvedEndpoint): ConnectionTestResult {
        if (endpoint.providerType.protocol == ProviderProtocol.FIREBASE_AI_PLACEHOLDER) {
            return ConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = builtInGeminiNotice.unavailableMessage()
            )
        }
        val probe = AiRequest(
            systemPrompt = "You are a connectivity probe.",
            userPrompt = "Reply with OK.",
            preferredModel = endpoint.model,
            temperature = 0.0,
            maxTokens = 16
        )
        return complete(probe, endpoint).fold(
            onSuccess = { response ->
                ConnectionTestResult(
                    success = true,
                    latencyMs = response.latencyMs,
                    message = "Connexion validée (${response.latencyMs} ms) — modèle ${response.modelUsed}",
                    modelResponded = response.modelUsed
                )
            },
            onFailure = { error ->
                ConnectionTestResult(
                    success = false,
                    latencyMs = 0L,
                    message = error.message ?: "Échec du test de connexion."
                )
            }
        )
    }

    private fun buildOpenAiRequest(request: AiRequest, endpoint: ResolvedEndpoint): Request {
        val url = "${endpoint.baseUrl.trimEnd('/')}/chat/completions"
        val messages = JSONArray()
        if (request.systemPrompt.isNotBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", request.systemPrompt)
            })
        }
        val userContent: Any = if (!request.imageBase64.isNullOrBlank()) {
            JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", request.userPrompt)
                })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:${request.imageMimeType};base64,${request.imageBase64}")
                    })
                })
            }
        } else {
            request.userPrompt
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        val payload = JSONObject().apply {
            put("model", endpoint.model)
            put("messages", messages)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            if (request.jsonMode) {
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }
        }

        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${endpoint.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (endpoint.providerType == ProviderType.OPENROUTER) {
            builder.addHeader("X-Title", "ForgeAI Android")
        }
        return builder.build()
    }

    private fun buildAnthropicRequest(request: AiRequest, endpoint: ResolvedEndpoint): Request {
        val url = "${endpoint.baseUrl.trimEnd('/')}/messages"
        val userContent: Any = if (!request.imageBase64.isNullOrBlank()) {
            JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", request.imageMimeType)
                        put("data", request.imageBase64)
                    })
                })
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", request.userPrompt)
                })
            }
        } else {
            request.userPrompt
        }

        val payload = JSONObject().apply {
            put("model", endpoint.model)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            if (request.systemPrompt.isNotBlank()) {
                put("system", request.systemPrompt)
            }
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
        }

        return Request.Builder()
            .url(url)
            .addHeader("x-api-key", endpoint.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun parseOpenAiResponse(
        rawBody: String,
        endpoint: ResolvedEndpoint,
        latencyMs: Long
    ): AiResponse {
        val root = JSONObject(rawBody)
        val choices = root.optJSONArray("choices")
            ?: throw AiGatewayException(AiErrorKind.INVALID_RESPONSE, "Champ 'choices' manquant.")
        if (choices.length() == 0) {
            throw AiGatewayException(AiErrorKind.INVALID_RESPONSE, "Aucune réponse générée.")
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
        val content = message?.optString("content").orEmpty()
        if (content.isBlank()) {
            throw AiGatewayException(AiErrorKind.INVALID_RESPONSE, "Contenu de réponse vide.")
        }
        val usage = root.optJSONObject("usage")
        val tokens = usage?.optLong("total_tokens", 0L) ?: 0L
        val modelUsed = root.optString("model").ifBlank { endpoint.model }
        return AiResponse(
            content = content,
            modelUsed = modelUsed,
            providerName = endpoint.providerName,
            keyLabel = endpoint.keyLabel,
            latencyMs = latencyMs,
            tokensUsed = tokens
        )
    }

    private fun parseAnthropicResponse(
        rawBody: String,
        endpoint: ResolvedEndpoint,
        latencyMs: Long
    ): AiResponse {
        val root = JSONObject(rawBody)
        val contentArray = root.optJSONArray("content")
            ?: throw AiGatewayException(AiErrorKind.INVALID_RESPONSE, "Champ 'content' manquant.")
        val textBuilder = StringBuilder()
        for (i in 0 until contentArray.length()) {
            val block = contentArray.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                textBuilder.append(block.optString("text"))
            }
        }
        val content = textBuilder.toString()
        if (content.isBlank()) {
            throw AiGatewayException(AiErrorKind.INVALID_RESPONSE, "Réponse Anthropic vide.")
        }
        val usage = root.optJSONObject("usage")
        val tokens = (usage?.optLong("input_tokens", 0L) ?: 0L) +
            (usage?.optLong("output_tokens", 0L) ?: 0L)
        val modelUsed = root.optString("model").ifBlank { endpoint.model }
        return AiResponse(
            content = content,
            modelUsed = modelUsed,
            providerName = endpoint.providerName,
            keyLabel = endpoint.keyLabel,
            latencyMs = latencyMs,
            tokensUsed = tokens
        )
    }

    private fun classifyHttpError(code: Int, body: String, providerName: String): AiGatewayException {
        val extractedMessage = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"

        return when (code) {
            401 -> AiGatewayException(
                kind = AiErrorKind.INVALID_API_KEY,
                message = "Clé API invalide ou expirée sur $providerName ($extractedMessage)",
                httpCode = code
            )
            403 -> AiGatewayException(
                kind = AiErrorKind.PERMISSION_DENIED,
                message = "Accès refusé par $providerName ($extractedMessage)",
                httpCode = code
            )
            429 -> AiGatewayException(
                kind = AiErrorKind.QUOTA_EXCEEDED,
                message = "Quota ou limite de débit atteinte sur $providerName ($extractedMessage)",
                httpCode = code
            )
            in 500..599 -> AiGatewayException(
                kind = AiErrorKind.SERVER_ERROR,
                message = "Erreur serveur $providerName ($code : $extractedMessage)",
                httpCode = code
            )
            else -> AiGatewayException(
                kind = AiErrorKind.INVALID_RESPONSE,
                message = "Requête rejetée par $providerName ($code : $extractedMessage)",
                httpCode = code
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
