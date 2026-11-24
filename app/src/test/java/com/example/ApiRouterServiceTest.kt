package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.ai.AiErrorKind
import com.example.core.ai.AiGateway
import com.example.core.ai.AiGatewayException
import com.example.core.ai.AiRequest
import com.example.core.ai.AiResponse
import com.example.core.ai.ApiRouterService
import com.example.core.ai.ConnectionTestResult
import com.example.core.ai.LoadBalancingStrategy
import com.example.core.ai.ProviderType
import com.example.core.ai.ResolvedEndpoint
import com.example.core.data.local.ForgeDatabase
import com.example.core.data.repository.ApiManagerRepository
import com.example.core.security.SecretCipher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApiRouterServiceTest {

    private lateinit var db: ForgeDatabase
    private lateinit var apiRepo: ApiManagerRepository

    private val passThroughCipher = object : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:$plaintext"
        override fun decrypt(encodedPayload: String): String = encodedPayload.removePrefix("enc:")
    }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ForgeDatabase::class.java
        ).allowMainThreadQueries().build()
        apiRepo = ApiManagerRepository(db.apiManagerDao(), passThroughCipher)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `rotates to backup key automatically on 429 quota exceeded`() = runTest {
        val provider = apiRepo.addProvider(
            name = "OpenAI",
            providerType = ProviderType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            modelPriority = listOf("gpt-4o-mini"),
            priority = 1
        ).getOrThrow()

        val key1 = apiRepo.addApiKey(provider.id, "Clé 1", "sk-first-secret-key-111", priority = 1).getOrThrow()
        val key2 = apiRepo.addApiKey(provider.id, "Clé 2", "sk-second-secret-key-222", priority = 2).getOrThrow()

        val fakeGateway = object : AiGateway {
            override suspend fun complete(request: AiRequest, endpoint: ResolvedEndpoint): Result<AiResponse> {
                return if (endpoint.keyId == key1.id) {
                    Result.failure(AiGatewayException(AiErrorKind.QUOTA_EXCEEDED, "Rate limit 429", 429))
                } else {
                    Result.success(
                        AiResponse(
                            content = "OK from key 2",
                            modelUsed = endpoint.model,
                            providerName = endpoint.providerName,
                            keyLabel = endpoint.keyLabel,
                            latencyMs = 120L,
                            tokensUsed = 42L
                        )
                    )
                }
            }

            override suspend fun testConnection(endpoint: ResolvedEndpoint): ConnectionTestResult =
                ConnectionTestResult(true, 100L, "OK")
        }

        val router = ApiRouterService(apiRepo, fakeGateway)
        val result = router.executeWithFailover(
            request = AiRequest("sys", "hello"),
            strategy = LoadBalancingStrategy.PRIORITY_AND_ROTATION
        )

        assertTrue(result.isSuccess)
        assertEquals("Clé 2", result.getOrThrow().keyLabel)
        val updatedSnapshot = apiRepo.getProvidersSnapshot().first().keys.associateBy { it.id }
        assertEquals(1L, updatedSnapshot[key1.id]?.totalErrors)
        assertEquals(1L, updatedSnapshot[key2.id]?.totalRequests)
        assertEquals(42L, updatedSnapshot[key2.id]?.totalTokens)
    }
}
