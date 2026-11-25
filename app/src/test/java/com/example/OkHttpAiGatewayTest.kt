package com.example

import com.example.core.ai.AiErrorKind
import com.example.core.ai.AiGatewayException
import com.example.core.ai.AiMessage
import com.example.core.ai.AiRequest
import com.example.core.ai.NetworkChecker
import com.example.core.ai.OkHttpAiGateway
import com.example.core.ai.ProviderType
import com.example.core.ai.ResolvedEndpoint
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OkHttpAiGatewayTest {

    private val alwaysOnline = object : NetworkChecker {
        override fun isOnline(): Boolean = true
    }

    private val alwaysOffline = object : NetworkChecker {
        override fun isOnline(): Boolean = false
    }

    private fun openAiEndpoint() = ResolvedEndpoint(
        providerId = "p1",
        providerName = "OpenAI",
        providerType = ProviderType.OPENAI,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        keyId = "k1",
        keyLabel = "Primary",
        apiKey = "sk-test-key"
    )

    private fun anthropicEndpoint() = ResolvedEndpoint(
        providerId = "p2",
        providerName = "Anthropic",
        providerType = ProviderType.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-3-5-sonnet-latest",
        keyId = "k2",
        keyLabel = "Claude",
        apiKey = "sk-ant-test"
    )

    private fun gatewayWithInterceptor(
        checker: NetworkChecker = alwaysOnline,
        handler: (okhttp3.Request, String) -> Response
    ): OkHttpAiGateway {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val bodyBuffer = Buffer()
                req.body?.writeTo(bodyBuffer)
                handler(req, bodyBuffer.readUtf8())
            })
            .build()
        return OkHttpAiGateway(checker, client)
    }

    @Test
    fun `complete serializes OpenAI conversationHistory and jsonMode and parses response`() = runTest {
        var capturedUrl = ""
        var capturedAuth = ""
        var capturedBody = ""

        val gateway = gatewayWithInterceptor { req, body ->
            capturedUrl = req.url.toString()
            capturedAuth = req.header("Authorization").orEmpty()
            capturedBody = body
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """
                    {
                      "model": "gpt-4o-mini-2024",
                      "choices": [{"message": {"role": "assistant", "content": "{\"ok\":true}"}}],
                      "usage": {"total_tokens": 75}
                    }
                    """.trimIndent().toResponseBody("application/json".toMediaType())
                )
                .build()
        }

        val request = AiRequest(
            systemPrompt = "Système strict",
            userPrompt = "Question finale",
            jsonMode = true,
            conversationHistory = listOf(
                AiMessage("user", "Tour 1"),
                AiMessage("assistant", "Réponse 1")
            )
        )

        val result = gateway.complete(request, openAiEndpoint())
        assertTrue(result.isSuccess)
        val resp = result.getOrThrow()
        assertEquals("{\"ok\":true}", resp.content)
        assertEquals(75L, resp.tokensUsed)
        assertEquals("gpt-4o-mini-2024", resp.modelUsed)
        assertEquals("https://api.openai.com/v1/chat/completions", capturedUrl)
        assertEquals("Bearer sk-test-key", capturedAuth)

        val payload = JSONObject(capturedBody)
        assertEquals("json_object", payload.getJSONObject("response_format").getString("type"))
        val messages = payload.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("user", messages.getJSONObject(3).getString("role"))
    }

    @Test
    fun `complete sends Anthropic headers and parses text blocks`() = runTest {
        var capturedUrl = ""
        var capturedApiKey = ""
        var capturedVersion = ""
        var capturedBody = ""

        val gateway = gatewayWithInterceptor { req, body ->
            capturedUrl = req.url.toString()
            capturedApiKey = req.header("x-api-key").orEmpty()
            capturedVersion = req.header("anthropic-version").orEmpty()
            capturedBody = body
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """
                    {
                      "model": "claude-3-5-sonnet-latest",
                      "content": [{"type": "text", "text": "Bonjour depuis Claude"}],
                      "usage": {"input_tokens": 12, "output_tokens": 18}
                    }
                    """.trimIndent().toResponseBody("application/json".toMediaType())
                )
                .build()
        }

        val result = gateway.complete(
            AiRequest(systemPrompt = "Aide précise", userPrompt = "Salut"),
            anthropicEndpoint()
        )
        assertTrue(result.isSuccess)
        val resp = result.getOrThrow()
        assertEquals("Bonjour depuis Claude", resp.content)
        assertEquals(30L, resp.tokensUsed)
        assertEquals("https://api.anthropic.com/v1/messages", capturedUrl)
        assertEquals("sk-ant-test", capturedApiKey)
        assertEquals("2023-06-01", capturedVersion)
        assertEquals("Aide précise", JSONObject(capturedBody).getString("system"))
    }

    @Test
    fun `parses Retry-After header on HTTP 429`() = runTest {
        val gateway = gatewayWithInterceptor { req, _ ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .addHeader("Retry-After", "45")
                .body("""{"error":{"message":"Rate limit hit"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = gateway.complete(AiRequest("sys", "hi"), openAiEndpoint())
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as AiGatewayException
        assertEquals(AiErrorKind.QUOTA_EXCEEDED, err.kind)
        assertEquals(429, err.httpCode)
        assertEquals(45_000L, err.retryAfterMs)
    }

    @Test
    fun `classifies HTTP 401 as INVALID_API_KEY and offline as OFFLINE`() = runTest {
        val gateway401 = gatewayWithInterceptor { req, _ ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"error":{"message":"Bad key"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val err401 = gateway401.complete(AiRequest("sys", "hi"), openAiEndpoint()).exceptionOrNull() as AiGatewayException
        assertEquals(AiErrorKind.INVALID_API_KEY, err401.kind)

        val gatewayOffline = gatewayWithInterceptor(checker = alwaysOffline) { _, _ ->
            error("Should not hit network when offline")
        }
        val errOffline = gatewayOffline.complete(AiRequest("sys", "hi"), openAiEndpoint()).exceptionOrNull() as AiGatewayException
        assertEquals(AiErrorKind.OFFLINE, errOffline.kind)
    }

    @Test
    fun `fetchAvailableModels parses and sorts model IDs`() = runTest {
        val gateway = gatewayWithInterceptor { req, _ ->
            assertEquals("https://api.openai.com/v1/models", req.url.toString())
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """
                    {
                      "data": [
                        {"id": "gpt-4o-mini"},
                        {"id": "gpt-4o"},
                        {"id": "gpt-4o-mini"}
                      ]
                    }
                    """.trimIndent().toResponseBody("application/json".toMediaType())
                )
                .build()
        }

        val result = gateway.fetchAvailableModels(openAiEndpoint())
        assertTrue(result.isSuccess)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), result.getOrThrow())
    }
}
